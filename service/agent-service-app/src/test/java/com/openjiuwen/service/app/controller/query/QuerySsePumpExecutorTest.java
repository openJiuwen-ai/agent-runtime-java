/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link QuerySsePumpExecutor} pool sizing and rejection policy.
 *
 * @since 0.1.0
 */
class QuerySsePumpExecutorTest {
    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void newPoolShouldUseSynchronousQueueAndAbortPolicy() {
        executor = QuerySsePumpExecutor.newPool(4);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
        assertThat(executor.getCorePoolSize()).isZero();
        assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class);
        assertThat(executor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    void newPoolShouldRejectWhenAllWorkersBusy() throws Exception {
        executor = QuerySsePumpExecutor.newPool(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch holdWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            awaitQuietly(holdWorker);
        });
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> executor.execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class);

        holdWorker.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
