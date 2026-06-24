/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.result.WriteFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxFsOperation;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of()))
                .isInstanceOf(ReadFileResult.class);
        assertThat(delegate.fs.readFileAttempts).isEqualTo(2);
    }

    @Test
    void writeFileDoesNotRetryBecauseItMayHaveSideEffects() {
        RecordingSandboxClient delegate = new RecordingSandboxClient();
        delegate.fs.failWriteFileAttempts = Integer.MAX_VALUE;
        AgentCoreExternalProperties.SandboxPolicy policy = policy();
        policy.getRetry().setMax(2);

        SandboxClient client = new DecoratingSandboxClient("sandbox-1", delegate, policy);

        assertThatThrownBy(() -> client.fs().writeFile(
                "/tmp/a.txt", "content", "text", false, false, true, null, "UTF-8", Map.of()))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThat(delegate.fs.writeFileAttempts).isEqualTo(1);
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

        assertThatThrownBy(() -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of()))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(() -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of()))
                .isInstanceOf(ExternalSvcAdapterException.class)
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

        assertThatThrownBy(() -> client.fs().readFile("/tmp/a.txt", "text", null, null, null, "UTF-8", 0, Map.of()))
                .isInstanceOf(ExternalSvcAdapterException.class)
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

        private RecordingSandboxClient() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public SandboxFsOperation fs() {
            return fs;
        }
    }

    private static final class RecordingSandboxFsOperation extends SandboxFsOperation {
        private int readFileAttempts;
        private int writeFileAttempts;
        private int failReadFileAttempts;
        private int failWriteFileAttempts;
        private long readFileSleepMs;

        private RecordingSandboxFsOperation() {
            super(SandboxGatewayConfig.builder().build());
        }

        @Override
        public ReadFileResult readFile(
                String path,
                String mode,
                Integer head,
                Integer tail,
                int[] lineRange,
                String encoding,
                int chunkSize,
                Map<String, Object> options) {
            readFileAttempts++;
            sleep(readFileSleepMs);
            if (readFileAttempts <= failReadFileAttempts) {
                throw new IllegalStateException("read boom");
            }
            return new ReadFileResult(0, "ok", null);
        }

        @Override
        public WriteFileResult writeFile(
                String path,
                Object content,
                String mode,
                boolean shouldPrependNewline,
                boolean shouldAppendNewline,
                boolean shouldCreateIfNotExist,
                String permissions,
                String encoding,
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
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                throw new IllegalStateException("Sandbox test sleep interrupted", ex);
            }
        }
    }
}
