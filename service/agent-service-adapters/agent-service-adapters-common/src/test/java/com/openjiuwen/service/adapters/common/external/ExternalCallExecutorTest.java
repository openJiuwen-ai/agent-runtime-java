/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests external call executor timeout and executor lifecycle behavior.
 *
 * @since 2026-06-24
 */
class ExternalCallExecutorTest {
    @Test
    void executeReusesConfiguredTimeoutExecutorWithoutShuttingItDownPerCall() {
        RecordingExecutorService timeoutExecutor = new RecordingExecutorService();
        ExternalCallExecutor executor = new ExternalCallExecutor(
                "test",
                "target",
                new TestPolicy(),
                ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN,
                ExternalSvcAdapterErrorCode.SANDBOX_RETRY_INTERRUPTED,
                ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT,
                timeoutExecutor);

        assertThat(executor.execute("fs", "readFile", true, () -> "one")).isEqualTo("one");
        assertThat(executor.execute("fs", "readFile", true, () -> "two")).isEqualTo("two");

        assertThat(timeoutExecutor.executeCalls).isEqualTo(2);
        assertThat(timeoutExecutor.shutdownNowCalls).isZero();
    }

    private static final class TestPolicy implements ExternalCallPolicy {
        private final ExternalRetryPolicy retry = new ExternalRetryPolicy();
        private final ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();
        private final ExternalAuditPolicy audit = new ExternalAuditPolicy();

        @Override
        public int getTimeoutMs() {
            return 1000;
        }

        @Override
        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        @Override
        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        @Override
        public ExternalAuditPolicy getAudit() {
            return audit;
        }
    }

    private static final class RecordingExecutorService extends AbstractExecutorService {
        private int executeCalls;
        private int shutdownNowCalls;
        private boolean isShutdown;

        @Override
        public void shutdown() {
            isShutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            isShutdown = true;
            shutdownNowCalls++;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return isShutdown;
        }

        @Override
        public boolean isTerminated() {
            return isShutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isShutdown;
        }

        @Override
        public void execute(Runnable command) {
            executeCalls++;
            command.run();
        }
    }
}
