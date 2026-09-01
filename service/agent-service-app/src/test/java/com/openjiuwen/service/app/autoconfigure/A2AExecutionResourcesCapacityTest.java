/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Capacity tests for the A2A agent execution pool and its coupling with the
 * admission guard (DFX-002): the pool default is auto-sized
 * {@code max(32, cores*8)} (not raw CPU cores), can be overridden via
 * {@code openjiuwen.service.a2a.agent-threads}, and startup fails fast when
 * the admission limit exceeds the pool capacity.
 */
class A2AExecutionResourcesCapacityTest {
    private static final String AGENT_THREADS = "openjiuwen.service.a2a.agent-threads";

    private static final String MAX_CONCURRENT = "openjiuwen.service.concurrency.max-concurrent-tasks";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
            .withBean(ServeOrchestrator.class, () -> org.mockito.Mockito.mock(ServeOrchestrator.class));

    @Test
    void autoPoolSizeFollowsSsePumpBaseline() {
        int cores = Runtime.getRuntime().availableProcessors();
        assertThat(A2AExecutionResources.autoAgentPoolSize())
                .as("auto pool size must be max(32, cores*8)")
                .isEqualTo(Math.max(32, cores * 8));
    }

    @Test
    void defaultCapacityIsAutoSizedNotRawCores() {
        contextRunner.run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertThat(resources.agentConcurrencyCapacity())
                    .as("default capacity must follow the I/O-friendly auto baseline")
                    .isEqualTo(A2AExecutionResources.autoAgentPoolSize())
                    .isGreaterThanOrEqualTo(32);
        });
    }

    @Test
    void configuredAgentThreadsOverrideAutoSizing() {
        contextRunner.withPropertyValues(AGENT_THREADS + "=50").run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertThat(resources.agentConcurrencyCapacity())
                    .as("agent-threads=50 must be honored exactly")
                    .isEqualTo(50);
        });
    }

    @Test
    void startupFailsWhenAdmissionLimitExceedsCapacity() {
        contextRunner
                .withPropertyValues(AGENT_THREADS + "=2")
                .withBean(TaskAdmissionGate.class, () -> new FixedLimitGate(3))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Task admission limit (3) exceeds")
                            .hasMessageContaining("agent-threads");
                });
    }

    @Test
    void startupSucceedsWhenAdmissionLimitWithinCapacity() {
        contextRunner
                .withPropertyValues(AGENT_THREADS + "=50")
                .withBean(TaskAdmissionGate.class, () -> new FixedLimitGate(50))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(A2AExecutionResources.class).agentConcurrencyCapacity())
                            .isEqualTo(50);
                });
    }

    @Test
    void eventConsumerExecutorScalesPerActiveStream() {
        contextRunner.run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            Executor executor = resources.eventConsumerExecutor();
            int streams = 16;
            CountDownLatch allRunning = new CountDownLatch(streams);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            for (int i = 0; i < streams; i++) {
                executor.execute(() -> {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    allRunning.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        throw new AssertionError("stream consumer interrupted while awaiting release latch", ex);
                    } finally {
                        active.decrementAndGet();
                    }
                });
            }
            try {
                assertThat(allRunning.await(10, TimeUnit.SECONDS))
                        .as("every active stream must own a consumer thread")
                        .isTrue();
                assertThat(peak.get())
                        .as("stream consumers must run concurrently, not on a fixed small pool")
                        .isEqualTo(streams);
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    void eventConsumerMaxThreadsTrackAgentPoolCapacity() {
        contextRunner.withPropertyValues(AGENT_THREADS + "=300").run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertThat(resources.eventConsumerMaxThreads())
                    .as("consumer cap must cover the full configured agent pool, not just the floor")
                    .isEqualTo(300);
        });
        contextRunner.withPropertyValues(AGENT_THREADS + "=50").run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertThat(resources.eventConsumerMaxThreads())
                    .as("small agent pools must still get the leak-guard floor")
                    .isEqualTo(256);
        });
    }

    @Test
    void eventConsumerExecutorRejectsBeyondHardCap() {
        contextRunner.run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            Executor executor = resources.eventConsumerExecutor();
            int cap = resources.eventConsumerMaxThreads();
            CountDownLatch release = new CountDownLatch(1);
            List<RejectedExecutionException> rejections = new CopyOnWriteArrayList<>();
            int submitted = 0;
            for (; submitted < cap + 8; submitted++) {
                try {
                    executor.execute(() -> {
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            throw new AssertionError("stream consumer interrupted while awaiting release latch", ex);
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    rejections.add(ex);
                    break;
                }
            }
            try {
                assertThat(rejections)
                        .as("submissions beyond the hard cap must be rejected loudly, not queued")
                        .hasSize(1);
                assertThat(submitted)
                        .as("rejection must occur exactly at the cap, threads never exceed it")
                        .isEqualTo(cap);
            } finally {
                release.countDown();
            }
        });
    }

    /** Minimal gate stub: only {@code limit()} matters for the startup guard. */
    static final class FixedLimitGate implements TaskAdmissionGate {
        private final int limit;

        FixedLimitGate(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {
        }

        @Override
        public int currentCount() {
            return 0;
        }

        @Override
        public int limit() {
            return limit;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void reset() {
        }
    }
}
