/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Tests JDK-compatible virtual-thread detection and executor creation.
 *
 * @since 0.1.0
 */
class VirtualThreadSupportTest {
    private static final int MINIMUM_VIRTUAL_THREAD_VERSION = 21;

    @Test
    void isSupportedShouldMatchCurrentJavaRuntime() {
        assertThat(VirtualThreadSupport.isSupported())
                .isEqualTo(Runtime.version().feature() >= MINIMUM_VIRTUAL_THREAD_VERSION);
    }

    @Test
    void newVirtualExecutorShouldRejectUnsupportedRuntime() {
        assumeFalse(VirtualThreadSupport.isSupported());

        assertThatThrownBy(() -> VirtualThreadSupport.newVirtualExecutor("test-virtual",
                VirtualThreadSupportTest::failOnUncaughtException)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void newVirtualExecutorShouldCreateNamedVirtualThread() throws InterruptedException, ExecutionException {
        assumeTrue(VirtualThreadSupport.isSupported());
        ExecutorService executor = VirtualThreadSupport.newVirtualExecutor("test-virtual",
                VirtualThreadSupportTest::failOnUncaughtException);
        try {
            Future<ThreadSnapshot> snapshotFuture = executor.submit(() -> snapshot(Thread.currentThread()));
            ThreadSnapshot snapshot = snapshotFuture.get();

            assertThat(snapshot.name()).startsWith("test-virtual-");
            assertThat(snapshot.isVirtual()).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void newVirtualExecutorShouldStartTasksWithoutAdmissionLimit() throws InterruptedException {
        assumeTrue(VirtualThreadSupport.isSupported());
        int taskCount = 128;
        ExecutorService executor = VirtualThreadSupport.newVirtualExecutor("test-unbounded",
                VirtualThreadSupportTest::failOnUncaughtException);
        CountDownLatch started = new CountDownLatch(taskCount);
        CompletableFuture<Void> release = new CompletableFuture<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                executor.execute(() -> {
                    started.countDown();
                    release.join();
                });
            }

            assertThat(started.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.complete(null);
            executor.shutdown();
        }
        assertThat(executor.awaitTermination(5L, TimeUnit.SECONDS)).isTrue();
    }

    private static ThreadSnapshot snapshot(Thread thread)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method isVirtual = Thread.class.getMethod("isVirtual");
        return new ThreadSnapshot(thread.getName(), Boolean.TRUE.equals(isVirtual.invoke(thread)));
    }

    private static void failOnUncaughtException(Thread thread, Throwable error) {
        throw new AssertionError("Unexpected failure in thread " + thread.getName(), error);
    }

    private record ThreadSnapshot(String name, boolean isVirtual) {
    }
}
