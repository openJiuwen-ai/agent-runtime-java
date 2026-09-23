/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

class InvocationPublisherTest {
    @Test
    void headersAreNormalizedImmutableSnapshotsAndRejectAmbiguity() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-USER-ID", "alice");
        A2aInvocationRequest request = new A2aInvocationRequest(null, "{}", headers);
        headers.put("X-USER-ID", "mallory");
        assertThat(request.headers()).containsExactlyEntriesOf(Map.of("x-user-id", "alice"));
        assertThatThrownBy(() -> request.headers().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        headers.put("x-user-id", "duplicate");
        assertThatThrownBy(() -> new A2aInvocationRequest(null, "{}", headers))
                .isInstanceOf(IllegalArgumentException.class);
        headers.clear();
        headers.put(null, "value");
        assertThatThrownBy(() -> new A2aInvocationRequest(null, "{}", headers))
                .isInstanceOf(IllegalArgumentException.class);
        headers.clear();
        headers.put("key", null);
        assertThatThrownBy(() -> new A2aInvocationRequest(null, "{}", headers))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new A2aInvocationRequest(null, null, null).headers()).isEmpty();
    }

    @Test
    void singleRequiresDemandAndRejectsSecondSubscriber() {
        var publisher = InvocationPublisher.single("json", new DefaultA2aRuntimeInvoker.InvocationCallContext());
        Probe first = new Probe();
        publisher.subscribe(first);
        assertThat(first.values).isEmpty();
        first.subscription.request(1);
        first.subscription.request(1);
        assertThat(first.values).containsExactly("json");
        assertThat(first.completions).isEqualTo(1);
        Probe second = new Probe();
        publisher.subscribe(second);
        assertThat(second.subscription).isNotNull();
        assertThat(second.error).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publisher.subscribe(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancellationBeforeDemandHandlesLateSdkRegistrationExactlyOnce() {
        var context = new DefaultA2aRuntimeInvoker.InvocationCallContext();
        Probe probe = new Probe();
        InvocationPublisher.single("json", context).subscribe(probe);
        probe.subscription.cancel();
        probe.subscription.cancel();
        AtomicInteger cancellations = new AtomicInteger();
        context.setEventConsumerCancelCallback(cancellations::incrementAndGet);
        context.invokeEventConsumerCancelCallback();
        probe.subscription.request(1);
        assertThat(cancellations).hasValue(1);
        assertThat(probe.values).isEmpty();
        assertThat(probe.completions).isZero();
    }

    @Test
    void invalidDemandAndSubscriberFailureCancelTheConsumer() {
        var context = new DefaultA2aRuntimeInvoker.InvocationCallContext();
        AtomicInteger cancelled = new AtomicInteger();
        context.setEventConsumerCancelCallback(cancelled::incrementAndGet);
        Probe probe = new Probe();
        InvocationPublisher.single("json", context).subscribe(probe);
        probe.subscription.request(0);
        probe.subscription.request(-1);
        assertThat(probe.error).isInstanceOf(IllegalArgumentException.class);
        assertThat(cancelled).hasValue(1);
        assertThat(probe.values).isEmpty();
        var other = new DefaultA2aRuntimeInvoker.InvocationCallContext();
        other.setEventConsumerCancelCallback(cancelled::incrementAndGet);
        Probe broken = new Probe() {
            @Override public void onNext(String value) { throw new IllegalStateException("subscriber failed"); }
        };
        InvocationPublisher.single("json", other).subscribe(broken);
        broken.subscription.request(1);
        assertThat(broken.completions).isZero();
        assertThat(cancelled).hasValue(2);
    }

    @Test
    void subscribeAndEncodingFailuresSignalOnceWithoutRetry() {
        AtomicInteger subscribed = new AtomicInteger();
        var context = new DefaultA2aRuntimeInvoker.InvocationCallContext();
        Flow.Publisher<String> source = subscriber -> {
            subscribed.incrementAndGet();
            throw new IllegalStateException("subscribe failed");
        };
        Probe probe = new Probe();
        new InvocationPublisher<>(source, value -> value, context).subscribe(probe);
        assertThat(probe.subscription).isNotNull();
        assertThat(probe.error).isInstanceOf(IllegalStateException.class);
        assertThat(subscribed).hasValue(1);
        Probe encoded = new Probe();
        new InvocationPublisher<>(InvocationPublisher.single("json", context), value -> {
            throw new IllegalStateException("encoding failed");
        }, context).subscribe(encoded);
        encoded.subscription.request(1);
        assertThat(encoded.error).hasMessage("encoding failed");
        assertThat(encoded.completions).isZero();
    }

    @Test
    void upstreamDemandErrorsAndDuplicateTerminalsDoNotLeakOrRetry() {
        var context = new DefaultA2aRuntimeInvoker.InvocationCallContext();
        AtomicInteger cancel = new AtomicInteger();
        context.setEventConsumerCancelCallback(cancel::incrementAndGet);
        Flow.Publisher<String> source = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                subscriber.onError(new IllegalStateException("upstream failed"));
                subscriber.onComplete();
                subscriber.onNext("late");
            }
            public void cancel() { throw new IllegalStateException("broken cancellation"); }
        });
        Probe probe = new Probe();
        new InvocationPublisher<>(source, value -> value, context).subscribe(probe);
        probe.subscription.request(1);
        assertThat(probe.error).hasMessage("upstream failed");
        assertThat(probe.values).isEmpty();
        assertThat(probe.completions).isZero();
        assertThat(cancel).hasValue(1);
    }

    @Test
    void cancellationRacingSdkCallbackRegistrationInvokesCallbackOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            var context = new DefaultA2aRuntimeInvoker.InvocationCallContext();
            AtomicInteger calls = new AtomicInteger();
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var register = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { barrier.await(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                context.setEventConsumerCancelCallback(calls::incrementAndGet);
            });
            barrier.await();
            context.invokeEventConsumerCancelCallback();
            register.get(5, java.util.concurrent.TimeUnit.SECONDS);
            context.invokeEventConsumerCancelCallback();
            assertThat(calls).hasValue(1);
        }
    }

    static class Probe implements Flow.Subscriber<String> {
        Flow.Subscription subscription;
        final List<String> values = new ArrayList<>();
        Throwable error;
        int completions;
        public void onSubscribe(Flow.Subscription value) { subscription = value; }
        public void onNext(String value) { values.add(value); }
        public void onError(Throwable value) { error = value; }
        public void onComplete() { completions++; }
    }
}
