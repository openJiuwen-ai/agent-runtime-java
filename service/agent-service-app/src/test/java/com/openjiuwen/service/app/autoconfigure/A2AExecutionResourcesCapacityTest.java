/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Capacity tests for the A2A agent execution pool and its coupling with the
 * admission guard (DFX-002): the platform pool default is auto-sized
 * {@code max(32, cores*8)} (not raw CPU cores), can be overridden via
 * {@code openjiuwen.service.a2a.agent-threads}, and startup fails fast when
 * the admission limit exceeds the pool capacity. Virtual executors have
 * no pool capacity limit, while business admission remains enabled.
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
        assumeFalse(VirtualThreadSupport.isSupported());
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
        assumeFalse(VirtualThreadSupport.isSupported());
        contextRunner.withPropertyValues(AGENT_THREADS + "=50").run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertThat(resources.agentConcurrencyCapacity())
                    .as("agent-threads=50 must be honored exactly")
                    .isEqualTo(50);
        });
    }

    @Test
    void startupFailsWhenAdmissionLimitExceedsCapacity() {
        assumeFalse(VirtualThreadSupport.isSupported());
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
        assumeFalse(VirtualThreadSupport.isSupported());
        contextRunner
                .withPropertyValues(AGENT_THREADS + "=50")
                .withBean(TaskAdmissionGate.class, () -> new FixedLimitGate(50))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(A2AExecutionResources.class).agentConcurrencyCapacity())
                            .isEqualTo(50);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void virtualCapacityDoesNotDependOnPlatformPoolSize(int configuredAgentThreads) {
        assumeTrue(VirtualThreadSupport.isSupported());
        contextRunner.withPropertyValues(AGENT_THREADS + "=" + configuredAgentThreads).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(A2AExecutionResources.class).agentConcurrencyCapacity())
                    .isEqualTo(Integer.MAX_VALUE);
        });
    }

    @Test
    void virtualExecutorsStartTasksBeyondPlatformPoolSize() {
        assumeTrue(VirtualThreadSupport.isSupported());
        contextRunner.withPropertyValues(AGENT_THREADS + "=2").run(context -> {
            A2AExecutionResources resources = context.getBean(A2AExecutionResources.class);
            assertTasksStartConcurrently(resources.agentExecutor());
            assertTasksStartConcurrently(resources.eventConsumerExecutor());
        });
    }

    @Test
    void virtualExecutorAllowsAdmissionLimitAbovePlatformPoolSize() {
        assumeTrue(VirtualThreadSupport.isSupported());
        contextRunner
                .withPropertyValues(AGENT_THREADS + "=2")
                .withBean(TaskAdmissionGate.class, () -> new FixedLimitGate(3))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(A2AExecutionResources.class).agentConcurrencyCapacity())
                            .isEqualTo(Integer.MAX_VALUE);
                    assertThat(context.getBean(TaskAdmissionGate.class).limit()).isEqualTo(3);
                });
    }

    private static void assertTasksStartConcurrently(Executor executor) throws InterruptedException {
        int taskCount = 3;
        CountDownLatch started = new CountDownLatch(taskCount);
        CompletableFuture<Void> release = new CompletableFuture<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                executor.execute(() -> {
                    started.countDown();
                    release.join();
                });
            }
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("all tasks must start without waiting for platform pool capacity")
                    .isTrue();
        } finally {
            release.complete(null);
        }
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
