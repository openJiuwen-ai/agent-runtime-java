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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the admission-rejection retry of {@link A2ATaskContinuation}.
 *
 * @since 0.1.0
 */
class A2ATaskContinuationTest {
    private static final long RETRY_BASE_DELAY_MS = 20L;

    /** Quiet period longer than the full backoff chain (20+40+80+160+320 ms). */
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
