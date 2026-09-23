/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-consumer adapter; never re-dispatches or buffers the SDK event stream. */
final class InvocationPublisher<T> implements Flow.Publisher<String> {
    @FunctionalInterface
    interface Encoder<T> {
        String encode(T value) throws Exception;
    }

    private static final Flow.Subscription EMPTY = new Flow.Subscription() {
        public void request(long n) { }
        public void cancel() { }
    };

    private final Flow.Publisher<T> source;
    private final Encoder<T> encoder;
    private final DefaultA2aRuntimeInvoker.InvocationCallContext context;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    InvocationPublisher(Flow.Publisher<T> source, Encoder<T> encoder,
            DefaultA2aRuntimeInvoker.InvocationCallContext context) {
        this.source = source;
        this.encoder = encoder;
        this.context = context;
    }

    static Flow.Publisher<String> single(String json, DefaultA2aRuntimeInvoker.InvocationCallContext context) {
        Flow.Publisher<String> source = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean done = new AtomicBoolean();

            public void request(long n) {
                if (done.compareAndSet(false, true)) {
                    if (n <= 0) {
                        subscriber.onError(new IllegalArgumentException("Demand must be positive"));
                    } else {
                        subscriber.onNext(json);
                        subscriber.onComplete();
                    }
                }
            }

            public void cancel() {
                done.set(true);
            }
        });
        return new InvocationPublisher<>(source, value -> value, context);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super String> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(EMPTY);
            subscriber.onError(new IllegalStateException("Only one subscriber is allowed"));
            return;
        }
        Bridge bridge = new Bridge(subscriber);
        try {
            source.subscribe(bridge);
        } catch (RuntimeException error) {
            bridge.fail(error);
        }
    }

    private final class Bridge implements Flow.Subscriber<T>, Flow.Subscription {
        private final Flow.Subscriber<? super String> downstream;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Flow.Subscription upstream;
        private boolean announced;

        Bridge(Flow.Subscriber<? super String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (upstream != null || done.get()) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            announced = true;
            try {
                downstream.onSubscribe(this);
            } catch (RuntimeException error) {
                cancel();
            }
        }

        @Override
        public void request(long n) {
            if (done.get()) {
                return;
            }
            if (n <= 0) {
                fail(new IllegalArgumentException("Demand must be positive"));
                return;
            }
            try {
                upstream.request(n);
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                cleanup();
            }
        }

        private void cleanup() {
            try {
                if (upstream != null) {
                    upstream.cancel();
                }
            } catch (RuntimeException ignored) {
                // Cancellation is best effort; still stop the SDK event consumer below.
            }
            try {
                context.invokeEventConsumerCancelCallback();
            } catch (RuntimeException ignored) {
                // A broken cancellation callback must not suppress the downstream error signal.
            }
        }

        @Override
        public void onNext(T item) {
            if (done.get()) {
                return;
            }
            String json;
            try {
                json = encoder.encode(item);
            } catch (Exception error) {
                fail(error);
                return;
            }
            try {
                downstream.onNext(json);
            } catch (RuntimeException error) {
                cancel();
            }
        }

        @Override
        public void onError(Throwable error) {
            fail(error);
        }

        void fail(Throwable error) {
            if (done.compareAndSet(false, true)) {
                cleanup();
                if (!announced) {
                    announced = true;
                    downstream.onSubscribe(EMPTY);
                }
                downstream.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (done.compareAndSet(false, true)) {
                try {
                    downstream.onComplete();
                } catch (RuntimeException error) {
                    cleanup();
                }
            }
        }
    }
}
