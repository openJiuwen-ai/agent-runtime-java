/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.result.WriteFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxCodeOperation;
import com.openjiuwen.core.sysop.sandbox.SandboxFsOperation;
import com.openjiuwen.core.sysop.sandbox.SandboxShellOperation;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests sandbox client decoration behavior for external adapter policies.
 *
 * @since 2026-06-24
 */
class DecoratingSandboxClientTest {
    @Test
    void readFileRetriesBecauseItIsSafeOperation() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.failReadFileAttempts = 1;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getRetry().setMax(1);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThat(client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of())).isInstanceOf(
            ReadFileResult.class);
        assertThat(delegate.fs.readFileAttempts).isEqualTo(2);
    }

    @Test
    void readFileRespectsRetryBackoffBeforeSucceeding() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.failReadFileAttempts = 1;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getRetry().setMax(1);
        policy.getRetry().setBackoffMs(80);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        long startNanos = System.nanoTime();
        ReadFileResult result = client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(result.getCode()).isZero();
        assertThat(delegate.fs.readFileAttempts).isEqualTo(2);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(70);
    }

    @Test
    void writeFileDoesNotRetryBecauseItMayHaveSideEffects() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.failWriteFileAttempts = Integer.MAX_VALUE;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getRetry().setMax(2);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThatThrownBy(() -> client.fs()
            .writeFile("/tmp/a.txt", "content", "text", false, false, true, null, "UTF-8", Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThat(delegate.fs.writeFileAttempts).isEqualTo(1);
    }

    @Test
    void shellAndCodeOperationsDoNotRetryBecauseTheyMayHaveSideEffects() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.shell.failExecuteCmdAttempts = Integer.MAX_VALUE;
        delegate.code.failExecuteCodeAttempts = Integer.MAX_VALUE;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getRetry().setMax(2);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThatThrownBy(() -> client.shell().executeCmd("echo sandbox", ".", 0, Map.of(), Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(
            () -> client.code().executeCode("print('sandbox')", "python", 0, Map.of(), Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThat(delegate.shell.executeCmdAttempts).isEqualTo(1);
        assertThat(delegate.code.executeCodeAttempts).isEqualTo(1);
    }

    @Test
    void shellAndCodeOperationsUsePolicyTimeoutWhenCallTimeoutIsMissing() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.setTimeoutMs(2500);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThat(client.shell().executeCmd("echo sandbox", ".", 0, Map.of(), Map.of())).isInstanceOf(
            ExecuteCmdResult.class);
        assertThat(client.code().executeCode("print('sandbox')", "python", 0, Map.of(), Map.of())).isInstanceOf(
            ExecuteCodeResult.class);
        assertThat(delegate.shell.lastExecuteCmdTimeout).isEqualTo(3);
        assertThat(delegate.code.lastExecuteCodeTimeout).isEqualTo(3);
    }

    @Test
    void circuitBreakerFailsFastAfterThreshold() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.failReadFileAttempts = Integer.MAX_VALUE;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThatThrownBy(
            () -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(
            () -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);
        assertThat(delegate.fs.readFileAttempts).isEqualTo(1);
    }

    @Test
    void timeoutWrapsSlowSandboxOperation() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.readFileSleepMs = 100;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.setTimeoutMs(20);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThatThrownBy(
            () -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of())).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT);
    }

    private AgentCoreExternalProperties.SandboxPolicy policy() {
        AgentCoreExternalProperties.SandboxPolicy policy = new AgentCoreExternalProperties.SandboxPolicy();
        policy.setEnabled(true);
        policy.setTimeoutMs(1000);
        return policy;
    }

    private static final class RecordingSandboxClient extends SandboxClient {
        private final RecordingSandboxFsOperation fs = new RecordingSandboxFsOperation();

        private final RecordingSandboxShellOperation shell = new RecordingSandboxShellOperation();

        private final RecordingSandboxCodeOperation code = new RecordingSandboxCodeOperation();

        private RecordingSandboxClient() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public SandboxFsOperation fs() {
            return fs;
        }

        @Override
        public SandboxShellOperation shell() {
            return shell;
        }

        @Override
        public SandboxCodeOperation code() {
            return code;
        }
    }

    private static final class RecordingSandboxFsOperation extends SandboxFsOperation {
        private final AtomicBoolean isInterrupted = new AtomicBoolean(false);

        private int readFileAttempts;

        private int writeFileAttempts;

        private int failReadFileAttempts;

        private int failWriteFileAttempts;

        private long readFileSleepMs;

        private RecordingSandboxFsOperation() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public ReadFileResult readFile(String path, String mode, Integer head, Integer tail, int[] lineRange,
            String encoding, int chunkSize, Map<String, Object> options) {
            readFileAttempts++;
            sleep(readFileSleepMs);
            if (readFileAttempts <= failReadFileAttempts) {
                throw new IllegalStateException("read boom");
            }
            return new ReadFileResult(0, "ok", null);
        }

        @Override
        public WriteFileResult writeFile(String path, Object content, String mode, boolean shouldPrependNewline,
            boolean shouldAppendNewline, boolean shouldCreate, String permissions, String encoding,
            Map<String, Object> options) {
            writeFileAttempts++;
            if (writeFileAttempts <= failWriteFileAttempts) {
                throw new IllegalStateException("write boom");
            }
            return new WriteFileResult(0, "ok", null);
        }

        private void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            long startTime = System.currentTimeMillis();
            while (!isInterrupted.get() && (System.currentTimeMillis() - startTime) < millis) {
                // 使用更高效的等待机制（如 LockSupport.parkNanos()）
                java.util.concurrent.locks.LockSupport.parkNanos(1000000); // 等待1ms，避免忙等待
            }
            if (isInterrupted.get()) {
                throw new IllegalStateException("Sandbox test sleep interrupted");
            }
        }
    }

    private static final class RecordingSandboxShellOperation extends SandboxShellOperation {
        private int executeCmdAttempts;

        private int failExecuteCmdAttempts;

        private int lastExecuteCmdTimeout;

        private RecordingSandboxShellOperation() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public ExecuteCmdResult executeCmd(String command, String cwd, int timeout, Map<String, String> environment,
            Map<String, Object> options) {
            executeCmdAttempts++;
            lastExecuteCmdTimeout = timeout;
            if (executeCmdAttempts <= failExecuteCmdAttempts) {
                throw new IllegalStateException("shell boom");
            }
            return new ExecuteCmdResult(0, "ok", null);
        }
    }

    private static final class RecordingSandboxCodeOperation extends SandboxCodeOperation {
        private int executeCodeAttempts;

        private int failExecuteCodeAttempts;

        private int lastExecuteCodeTimeout;

        private RecordingSandboxCodeOperation() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public ExecuteCodeResult executeCode(String code, String language, int timeout, Map<String, String> environment,
            Map<String, Object> options) {
            executeCodeAttempts++;
            lastExecuteCodeTimeout = timeout;
            if (executeCodeAttempts <= failExecuteCodeAttempts) {
                throw new IllegalStateException("code boom");
            }
            return new ExecuteCodeResult(0, "ok", null);
        }
    }
}