/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.runner.drunner.remoteclient.ProtocolEnum;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tests remote client decoration behavior for external adapter policies.
 *
 * @since 2026-06-24
 */
class DecoratingRemoteClientTest {
    @Test
    void startStopAndStartedStateDelegateToA2ARemoteClient() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy());

        assertThat(client.isStarted()).isFalse();

        client.start();
        assertThat(client.isStarted()).isTrue();
        assertThat(delegate.startCalls).isEqualTo(1);

        client.stop();
        assertThat(client.isStarted()).isFalse();
        assertThat(delegate.stopCalls).isEqualTo(1);
    }

    @Test
    void invokeRetriesOnlyWhenExplicitlyEnabledAndUsesConfiguredTimeoutWhenCallerDoesNotProvideOne() throws Exception {
        RecordingRemoteClient delegate = new RecordingRemoteClient(1);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.setTimeoutMs(2500);
        policy.setRetryInvoke(true);
        policy.getRetry().setMax(1);
        policy.getRetry().setBackoffMs(10);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        long startNanos = System.nanoTime();
        Object result = client.invoke(Map.of("query", "hi"), null);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(result).isEqualTo(Map.of("ok", true));
        assertThat(delegate.invokeAttempts).isEqualTo(2);
        assertThat(delegate.lastTimeoutSeconds).isEqualTo(2.5d);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(8);
    }

    @Test
    void invokeUsesHardTimeoutWhenDelegateIgnoresTimeout() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        delegate.invokeDelayMs = 250;
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.setTimeoutMs(50);
        policy.getRetry().setMax(0);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_TIMEOUT);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void invokeDoesNotRetryByDefaultBecauseItMayHaveSideEffects() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.getRetry().setMax(2);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_OUTBOUND_CALL_FAILED);
        assertThat(delegate.invokeAttempts).isEqualTo(1);
    }

    @Test
    void invokeKeepsCallerProvidedTimeout() throws Exception {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.setTimeoutMs(2500);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        client.invoke(Map.of("query", "hi"), 7.0d);

        assertThat(delegate.lastTimeoutSeconds).isEqualTo(7.0d);
    }

    @Test
    void invokeWrapsFinalFailureWithRemoteErrorCode() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.setRetryInvoke(true);
        policy.getRetry().setMax(1);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_OUTBOUND_CALL_FAILED);
        assertThat(delegate.invokeAttempts).isEqualTo(2);
    }

    @Test
    void circuitBreakerFailsFastAfterThreshold() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_CIRCUIT_OPEN);
        assertThat(delegate.invokeAttempts).isEqualTo(1);
    }

    @Test
    void circuitBreakerAllowsRequestAfterResetWindowAndClearsFailures() throws Exception {
        RecordingRemoteClient delegate = new RecordingRemoteClient(1);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(1);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        assertThatThrownBy(() -> client.invoke(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_OUTBOUND_CALL_FAILED);

        Thread.sleep(5);

        assertThat(client.invoke(Map.of("query", "hi"), null)).isEqualTo(Map.of("ok", true));
        assertThat(delegate.invokeAttempts).isEqualTo(2);
    }

    @Test
    void streamUsesConfiguredTimeoutAndReturnsDelegateIterator() throws Exception {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.setTimeoutMs(2500);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        Iterator<Object> iterator = client.stream(Map.of("query", "hi"), null);

        assertThat(iterator).toIterable().containsExactly("chunk");
        assertThat(delegate.streamAttempts).isEqualTo(1);
        assertThat(delegate.lastTimeoutSeconds).isEqualTo(2.5d);
    }

    @Test
    void streamWrapsFailureWithoutRetryingRemoteExecution() {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        delegate.shouldFailStream = true;
        AgentCoreExternalProperties.RemotePolicy policy = policy();
        policy.getRetry().setMax(2);

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);

        assertThatThrownBy(() -> client.stream(Map.of("query", "hi"), null)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_STREAM_FAILED);
        assertThat(delegate.streamAttempts).isEqualTo(1);
    }

    @Test
    void streamWrapsLazyIteratorFailureWithRemoteErrorCode() throws Exception {
        RecordingRemoteClient delegate = new RecordingRemoteClient(0);
        delegate.shouldFailDuringStreamIteration = true;
        AgentCoreExternalProperties.RemotePolicy policy = policy();

        DecoratingRemoteClient client = new DecoratingRemoteClient(config(), delegate, policy);
        Iterator<Object> iterator = client.stream(Map.of("query", "hi"), null);

        assertThat(iterator.hasNext()).isTrue();
        assertThatThrownBy(iterator::next).isInstanceOf(ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.REMOTE_STREAM_FAILED);
        assertThat(delegate.streamAttempts).isEqualTo(1);
    }

    private RemoteClientConfig config() {
        return RemoteClientConfig.builder()
            .id("a2a-agent")
            .name("A2A Agent")
            .protocol(ProtocolEnum.A2A)
            .url("http://localhost:18081/a2a")
            .build();
    }

    private AgentCoreExternalProperties.RemotePolicy policy() {
        return new AgentCoreExternalProperties.RemotePolicy();
    }

    private static final class RecordingRemoteClient implements RemoteClient {
        private final int failuresBeforeSuccess;

        private int invokeAttempts;

        private int streamAttempts;

        private int startCalls;

        private int stopCalls;

        private boolean isStarted;

        private boolean shouldFailStream;

        private boolean shouldFailDuringStreamIteration;

        private long invokeDelayMs;

        private Double lastTimeoutSeconds;

        private RecordingRemoteClient(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public void start() {
            startCalls++;
            isStarted = true;
        }

        @Override
        public void stop() {
            stopCalls++;
            isStarted = false;
        }

        @Override
        public boolean isStarted() {
            return isStarted;
        }

        @Override
        public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) {
            invokeAttempts++;
            lastTimeoutSeconds = timeoutSeconds;
            sleepIgnoringInterrupts(invokeDelayMs);
            if (invokeAttempts <= failuresBeforeSuccess) {
                throw new IllegalStateException("remote boom");
            }
            return Map.of("ok", true);
        }

        @Override
        public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
            streamAttempts++;
            lastTimeoutSeconds = timeoutSeconds;
            if (shouldFailStream) {
                throw new IllegalStateException("stream boom");
            }
            if (shouldFailDuringStreamIteration) {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public Object next() {
                        throw new IllegalStateException("lazy stream boom");
                    }
                };
            }
            return List.<Object>of("chunk").iterator();
        }

        private static void sleepIgnoringInterrupts(long delayMs) {
            long deadline = System.nanoTime() + delayMs * 1_000_000L;
            while (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    // Keep sleeping to prove the decorator owns the timeout.
                }
                delayMs = (deadline - System.nanoTime()) / 1_000_000L;
            }
        }
    }
}
