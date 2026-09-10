/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the admission-rejection retry of {@link A2ATaskContinuation}.
 *
 * @since 0.1.0
 */
class A2ATaskContinuationTest {
    private static final long RETRY_BASE_DELAY_MS = 20L;

    /**
     * Quiet period longer than the full backoff chain (20+40+80+160+320 ms).
     */
    private static final long QUIET_PERIOD_MS = 1500L;

    private static final String TASK_ID = "parent-1";

    private static final String BATCH_ID = "batch-1";

    private TaskStore taskStore;

    private A2AAgentExecutor agentExecutor;

    private A2ATaskContinuation continuation;

    @BeforeEach
    void setUp() {
        taskStore = mock(TaskStore.class);
        agentExecutor = mock(A2AAgentExecutor.class);
        QueueManager queueManager = new InMemoryQueueManager(null, new MainEventBus());
        @SuppressWarnings("unchecked")
        ObjectProvider<A2AAgentExecutor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agentExecutor);
        when(taskStore.get(TASK_ID)).thenReturn(inputRequiredTask());
        continuation = new A2ATaskContinuation(taskStore, queueManager, provider, Runnable::run,
                RETRY_BASE_DELAY_MS);
    }

    @AfterEach
    void tearDown() {
        continuation.shutdown();
    }

    @Test
    void admissionRejection_retriesWithBackoff_andSucceeds() throws Exception {
        CountDownLatch succeeded = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw admissionRejected();
            }
            succeeded.countDown();
            return null;
        }).when(agentExecutor).continueTask(any(), any(), any());

        continuation.submit(request());

        assertThat(succeeded.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(QUIET_PERIOD_MS);
        verify(agentExecutor, times(2)).continueTask(any(), any(), any());
    }

    @Test
    void retryBudgetExhausted_thenAcceptsResubmit() throws Exception {
        CountDownLatch resubmitSucceeded = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call <= 6) {
                throw admissionRejected();
            }
            resubmitSucceeded.countDown();
            return null;
        }).when(agentExecutor).continueTask(any(), any(), any());

        continuation.submit(request());

        awaitCallCount(calls, 6, 10);
        Thread.sleep(QUIET_PERIOD_MS);
        verify(agentExecutor, times(6)).continueTask(any(), any(), any());

        continuation.submit(request());
        assertThat(resubmitSucceeded.await(10, TimeUnit.SECONDS)).isTrue();
        verify(agentExecutor, times(7)).continueTask(any(), any(), any());
    }

    @Test
    void nonAdmissionError_noRetry_thenAcceptsResubmit() throws Exception {
        doThrow(new A2AError(A2AErrorCodes.INTERNAL.code(), "AGENT_EXECUTION_FAILED", null))
                .when(agentExecutor).continueTask(any(), any(), any());

        continuation.submit(request());
        verify(agentExecutor, times(1)).continueTask(any(), any(), any());
        Thread.sleep(QUIET_PERIOD_MS);
        verify(agentExecutor, times(1)).continueTask(any(), any(), any());

        continuation.submit(request());
        verify(agentExecutor, times(2)).continueTask(any(), any(), any());
        Thread.sleep(QUIET_PERIOD_MS);
        verify(agentExecutor, times(2)).continueTask(any(), any(), any());
    }

    @Test
    void retryChainHoldsMarker_duplicateSubmitSuppressed() throws Exception {
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondAttempt = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw admissionRejected();
            }
            secondAttemptStarted.countDown();
            releaseSecondAttempt.await();
            return null;
        }).when(agentExecutor).continueTask(any(), any(), any());

        continuation.submit(request());
        assertThat(secondAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();
        continuation.submit(request());
        releaseSecondAttempt.countDown();

        Thread.sleep(QUIET_PERIOD_MS);
        verify(agentExecutor, times(2)).continueTask(any(), any(), any());
    }

    @Test
    void saturatedExecutorDoesNotHoldShutdownLockDuringInlineContinuation() throws Exception {
        var releaseWorker = new CountDownLatch(1);
        var workerEntered = new CountDownLatch(1);
        var releaseContinuation = new CountDownLatch(1);
        var continuationEntered = new CountDownLatch(1);
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy());
        var callers = Executors.newFixedThreadPool(2);
        @SuppressWarnings("unchecked")
        ObjectProvider<A2AAgentExecutor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agentExecutor);
        continuation.shutdown();
        continuation = new A2ATaskContinuation(taskStore, new InMemoryQueueManager(null, new MainEventBus()),
                provider, executor, RETRY_BASE_DELAY_MS);
        doAnswer(invocation -> {
            continuationEntered.countDown();
            releaseContinuation.await();
            return null;
        }).when(agentExecutor).continueTask(any(), any(), any());
        try {
            var worker = executor.submit(() -> {
                workerEntered.countDown();
                releaseWorker.await();
                return null;
            });
            assertThat(workerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = executor.submit(() -> { });
            var submitted = callers.submit(() -> continuation.submit(request()));
            assertThat(continuationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // The continuation is still executing inline on the submitting thread.
            // Shutdown must return before that invocation is allowed to finish.
            callers.submit(continuation::shutdown).get(5, TimeUnit.SECONDS);
            assertThat(submitted.isDone()).isFalse();
            continuation.submit(request());
            releaseContinuation.countDown();
            submitted.get(5, TimeUnit.SECONDS);
            releaseWorker.countDown();
            worker.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
            verify(agentExecutor, times(1)).continueTask(any(), any(), any());
        } finally {
            releaseContinuation.countDown();
            releaseWorker.countDown();
            callers.shutdown();
            executor.shutdown();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void submittedContinuationDoesNotExecuteAfterShutdown() {
        var pending = new ArrayDeque<Runnable>();
        continuation.shutdown();
        continuation = new A2ATaskContinuation(taskStore, null, null, pending::add, RETRY_BASE_DELAY_MS);
        continuation.submit(request());
        assertThat(pending).hasSize(1);
        continuation.shutdown();
        pending.remove().run();
        org.mockito.Mockito.verifyNoInteractions(taskStore, agentExecutor);
    }

    @Test
    void stoppingBorrowerKeepsOtherRetriesAndSharedScheduler() throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        var firstExecutor = mock(A2AAgentExecutor.class);
        var secondExecutor = mock(A2AAgentExecutor.class);
        var firstCalls = new AtomicInteger();
        var secondCalls = new AtomicInteger();
        var succeeded = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstCalls.incrementAndGet();
            throw admissionRejected();
        }).when(firstExecutor).continueTask(any(), any(), any());
        doAnswer(invocation -> {
            if (secondCalls.incrementAndGet() == 1) {
                throw admissionRejected();
            }
            succeeded.countDown();
            return null;
        }).when(secondExecutor).continueTask(any(), any(), any());
        var first = borrower(firstExecutor, scheduler);
        var second = borrower(secondExecutor, scheduler);
        var release = new CountDownLatch(1);
        try {
            var blocker = blockScheduler(scheduler, release);
            first.submit(request());
            second.submit(request());
            assertThat(scheduler.getQueue()).hasSize(2);
            first.shutdown();
            assertThat(scheduler.isShutdown()).isFalse();
            assertThat(scheduler.getQueue()).hasSize(1);
            first.submit(request());
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            assertThat(succeeded.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(2);
            second.shutdown();
            assertThat(scheduler.isShutdown()).isFalse();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            release.countDown();
            first.shutdown();
            second.shutdown();
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static java.util.concurrent.Future<?> blockScheduler(ScheduledThreadPoolExecutor scheduler,
            CountDownLatch release) throws InterruptedException {
        var started = new CountDownLatch(1);
        // Hold the scheduler until both continuations are queued and one has been stopped.
        var blocker = scheduler.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                throw new IllegalStateException("Retry test interrupted", failure);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return blocker;
    }

    private static A2ATaskContinuation borrower(A2AAgentExecutor executor, ScheduledThreadPoolExecutor scheduler) {
        var store = new org.a2aproject.sdk.server.tasks.InMemoryTaskStore();
        store.save(inputRequiredTask(), true);
        var provider = new ObjectProvider<A2AAgentExecutor>() {
            @Override
            public A2AAgentExecutor getObject() {
                return executor;
            }
        };
        return new A2ATaskContinuation(store, new InMemoryQueueManager(null, new MainEventBus()), provider,
                Runnable::run, scheduler);
    }

    private static A2AError admissionRejected() {
        return new A2AError(A2AErrorCodes.INTERNAL.code(), A2AAgentExecutor.ADMISSION_REJECTED_MESSAGE, null);
    }

    private static Task inputRequiredTask() {
        return Task.builder().id(TASK_ID).contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED)).build();
    }

    private static ServeRequest request() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setMetadata(Map.of("runtime.parentTaskId", TASK_ID, "runtime.remoteBatchId", BATCH_ID));
        return request;
    }

    private static void awaitCallCount(AtomicInteger counter, int expected, long timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (counter.get() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(counter.get()).isEqualTo(expected);
    }
}
