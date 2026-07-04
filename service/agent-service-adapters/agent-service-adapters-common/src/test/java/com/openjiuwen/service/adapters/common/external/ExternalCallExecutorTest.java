/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests external call executor timeout and executor lifecycle behavior.
 *
 * @since 2026-06-24
 */
class ExternalCallExecutorTest {
    @Test
    void executeReusesConfiguredTimeoutExecutorWithoutShuttingItDownPerCall() {
        RecordingExecutorService timeoutExecutor = new RecordingExecutorService();
        ExternalCallExecutor executor = new ExternalCallExecutor("test", "target", new TestPolicy(),
            ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED, ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.SANDBOX_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT,
            timeoutExecutor);

        assertThat(executor.execute("fs", "readFile", true, () -> "one")).isEqualTo("one");
        assertThat(executor.execute("fs", "readFile", true, () -> "two")).isEqualTo("two");

        assertThat(timeoutExecutor.executeCalls).isEqualTo(2);
        assertThat(timeoutExecutor.shutdownNowCalls).isZero();
    }

    @Test
    void retryPolicyRejectsNegativeValues() {
        ExternalRetryPolicy retry = new ExternalRetryPolicy();

        assertThatThrownBy(() -> retry.setMax(-1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry.max");
        assertThatThrownBy(() -> retry.setBackoffMs(-1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retry.backoff-ms");
    }

    @Test
    void circuitBreakerPolicyRejectsNonPositiveValues() {
        ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        assertThatThrownBy(() -> circuitBreaker.setFailureThreshold(0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failure-threshold");
        assertThatThrownBy(() -> circuitBreaker.setResetTimeoutMs(0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reset-timeout-ms");
    }

    @Test
    void executorRejectsNonPositiveTimeoutInsteadOfNormalizingIt() {
        TestPolicy policy = new TestPolicy(0);

        assertThatThrownBy(() -> executor(policy)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeout-ms");
    }

    @Test
    void executeOpensCircuitAfterFiveFailuresAndSkipsDelegateWithinWindow() {
        TestPolicy policy = new TestPolicy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(5);
        policy.getCircuitBreaker().setResetTimeoutMs(5000);
        AtomicInteger attempts = new AtomicInteger();
        ExternalCallExecutor executor = executor(policy);

        for (int index = 0; index < 5; index++) {
            assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("downstream unavailable");
            }), ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        }

        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            return "unexpected";
        }), ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);
        assertThat(attempts.get()).isEqualTo(5);
    }

    @Test
    void executeReopensCircuitWhenResetWindowProbeFails() throws InterruptedException {
        TestPolicy policy = new TestPolicy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);
        AtomicInteger attempts = new AtomicInteger();
        ExternalCallExecutor executor = executor(policy);

        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("first failure");
        }), ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> "blocked"),
            ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);

        policy.getCircuitBreaker().setResetTimeoutMs(20);
        Thread.sleep(30L);

        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("probe failure");
        }), ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);
        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> "blocked again"),
            ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void executeClosesCircuitWhenResetWindowProbeSucceeds() throws InterruptedException {
        TestPolicy policy = new TestPolicy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);
        AtomicInteger attempts = new AtomicInteger();
        ExternalCallExecutor executor = executor(policy);

        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("first failure");
        }), ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertExternalFailureCode(() -> executor.execute("mcp", "tools/list", false, () -> "blocked"),
            ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);

        policy.getCircuitBreaker().setResetTimeoutMs(20);
        Thread.sleep(30L);

        assertThat(executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            return "probe-ok";
        })).isEqualTo("probe-ok");
        assertThat(executor.execute("mcp", "tools/list", false, () -> {
            attempts.incrementAndGet();
            return "normal-ok";
        })).isEqualTo("normal-ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    private ExternalCallExecutor executor(TestPolicy policy) {
        return new ExternalCallExecutor("test", "target", policy,
            ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED, ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.SANDBOX_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT);
    }

    private static void assertExternalFailureCode(Runnable invocation, ExternalSvcAdapterErrorCode errorCode) {
        assertThatThrownBy(invocation::run).isInstanceOf(ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(errorCode);
    }

    private static final class TestPolicy implements ExternalCallPolicy {
        private final ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private final ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private final ExternalAuditPolicy audit = new ExternalAuditPolicy();

        private final int timeoutMs;

        private TestPolicy() {
            this(1000);
        }

        private TestPolicy(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public int getTimeoutMs() {
            return timeoutMs;
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
