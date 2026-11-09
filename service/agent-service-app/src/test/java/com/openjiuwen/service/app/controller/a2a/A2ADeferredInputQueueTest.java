/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class A2ADeferredInputQueueTest {
    private static final A2ADeferredInputQueue.Scope SCOPE = new A2ADeferredInputQueue.Scope("tenant", "space", "agent",
            "user", "conversation");

    @Test
    void parallelTasksMakeUntargetedInputAmbiguous() {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        var second = queue.begin(SCOPE, "task-b").orElseThrow();
        assertThat(queue.task(SCOPE)).isEmpty();
        queue.finish(first, true);
        assertThat(queue.task(SCOPE)).contains("task-b");
        queue.finish(second, true);
    }

    @Test
    void inputWaitsForCurrentOwnerAndRunsInOrder() {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        assertThat(queue.offer(SCOPE, "task-a", "m1", "暂停")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        assertThat(queue.offer(SCOPE, "task-a", "m2", "answer")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        assertThat(queue.begin(SCOPE, "task-a")).isEmpty();
        var next = queue.finish(first, false).orElseThrow();
        assertThat(next.input()).isEqualTo("暂停");
        assertThat(queue.begin(SCOPE, "task-a")).isEmpty();
        assertThat(queue.finish(next, false).orElseThrow().input()).isEqualTo("answer");
    }

    @Test
    void finalTaskDropsQueuedInputAndOldOwnerCannotReleaseSuccessor() {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var old = queue.begin(SCOPE, "task-a").orElseThrow();
        queue.offer(SCOPE, "task-a", "m1", "暂停");
        assertThat(queue.finish(old, true)).isEmpty();
        assertThat(queue.offer(SCOPE, "task-a", "m2", "late")).isEqualTo(A2ADeferredInputQueue.Offer.NO_TARGET);
        var successor = queue.begin(SCOPE, "task-b").orElseThrow();
        assertThat(queue.finish(old, true)).isEmpty();
        assertThat(queue.begin(SCOPE, "task-b")).isEmpty();
        assertThat(queue.finish(successor, true)).isEmpty();
    }

    @Test
    void idleTaskCanClaimQueuedInputWithoutReplayingDuplicate() {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        queue.finish(first, false);
        assertThat(queue.offer(SCOPE, "task-a", "m1", "暂停")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        var next = queue.claim(SCOPE, "task-a").orElseThrow();
        assertThat(next.input()).isEqualTo("暂停");
        assertThat(queue.offer(SCOPE, "task-a", "m1", "暂停")).isEqualTo(A2ADeferredInputQueue.Offer.DUPLICATE);
        assertThat(queue.finish(next, false)).isEmpty();
    }

    @Test
    void identityAndCapacityAreEnforcedWithoutEvictingPendingInput() {
        var queue = new A2ADeferredInputQueue<String>(1, 1);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        assertThat(queue.offer(SCOPE, "task-b", "m1", "wrong task")).isEqualTo(A2ADeferredInputQueue.Offer.NO_TARGET);
        var other = new A2ADeferredInputQueue.Scope("tenant", "space", "agent", "other-user", "conversation");
        assertThat(queue.offer(other, "task-a", "m1", "wrong user")).isEqualTo(A2ADeferredInputQueue.Offer.NO_TARGET);
        assertThat(queue.offer(SCOPE, "task-a", "m1", "暂停")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        assertThat(queue.offer(SCOPE, "task-a", "m2", "overflow")).isEqualTo(A2ADeferredInputQueue.Offer.FULL);
        assertThat(queue.begin(other, "task-b")).isEmpty();
        assertThat(queue.finish(first, false).orElseThrow().input()).isEqualTo("暂停");
    }

    @Test
    void simultaneousReleaseAndEnqueueNeverLoseAcceptedInput() throws Exception {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        var start = new CountDownLatch(1);
        var offer = CompletableFuture.supplyAsync(() -> {
            await(start);
            return queue.offer(SCOPE, "task-a", "m1", "暂停");
        });
        var finish = CompletableFuture.supplyAsync(() -> {
            await(start);
            return queue.finish(first, false);
        });
        start.countDown();
        assertThat(offer.get(5, TimeUnit.SECONDS)).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        var next = finish.get(5, TimeUnit.SECONDS).or(() -> queue.claim(SCOPE, "task-a")).orElseThrow();
        assertThat(next.input()).isEqualTo("暂停");
        assertThat(queue.claim(SCOPE, "task-a")).isEmpty();
    }

    @Test
    void idleWaitingRecordsDoNotExhaustExecutionCapacity() {
        var queue = new A2ADeferredInputQueue<String>(1, 4);
        var first = queue.begin(SCOPE, "old-waiting").orElseThrow();
        queue.release(first, false);
        assertThat(queue.begin(SCOPE, "new-task")).isPresent();
    }

    @Test
    void capacityReclamationNeverDropsAcceptedIdleInput() {
        var queue = new A2ADeferredInputQueue<String>(1, 4);
        var first = queue.begin(SCOPE, "waiting").orElseThrow();
        queue.release(first, false);
        assertThat(queue.offer(SCOPE, "waiting", "m1", "answer")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        assertThat(queue.begin(SCOPE, "new-task")).isEmpty();
        assertThat(queue.claim(SCOPE, "waiting").orElseThrow().input()).isEqualTo("answer");
    }

    @Test
    void restoredWaitingRecordRetainsDeduplicationAndCancellationFreesIt() {
        var queue = new A2ADeferredInputQueue<String>(1, 4);
        assertThat(queue.restore(SCOPE, "waiting", java.util.Set.of("used"))).isTrue();
        assertThat(queue.offer(SCOPE, "waiting", "used", "old")).isEqualTo(A2ADeferredInputQueue.Offer.DUPLICATE);
        assertThat(queue.offer(SCOPE, "waiting", "new", "pause")).isEqualTo(A2ADeferredInputQueue.Offer.ACCEPTED);
        queue.cancelTask("waiting");
        assertThat(queue.begin(SCOPE, "next")).isPresent();
    }

    @Test
    void cancellationDuringRoundDropsInputAfterOwnerRelease() {
        var queue = new A2ADeferredInputQueue<String>(1, 4);
        var owner = queue.begin(SCOPE, "task").orElseThrow();
        queue.offer(SCOPE, "task", "m1", "pause");
        queue.cancelTask("task");
        assertThat(queue.begin(SCOPE, "task")).isEmpty();
        assertThat(queue.release(owner, false)).isFalse();
        assertThat(queue.task(SCOPE)).isEmpty();
        assertThat(queue.begin(SCOPE, "next")).isPresent();
    }

    @Test
    void closeDropsPendingInputAndRejectsNewWork() {
        var queue = new A2ADeferredInputQueue<String>(4, 4);
        var first = queue.begin(SCOPE, "task-a").orElseThrow();
        queue.offer(SCOPE, "task-a", "m1", "暂停");
        queue.close();
        assertThat(queue.finish(first, false)).isEmpty();
        assertThat(queue.begin(SCOPE, "task-b")).isEmpty();
        assertThat(queue.offer(SCOPE, "task-a", "m2", "暂停")).isEqualTo(A2ADeferredInputQueue.Offer.CLOSED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
