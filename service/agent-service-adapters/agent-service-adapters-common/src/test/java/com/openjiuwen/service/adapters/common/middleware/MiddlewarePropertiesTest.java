/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

/**
 * MiddlewarePropertiesTest
 *
 * @since 2026-07-03
 */
class MiddlewarePropertiesTest {
    @Test
    void bindsCheckpointerAndPlaceholderCapabilitiesTogether() {
        Map<String, String> source = Map.of("openjiuwen.service.middleware.checkpointer.type", "redis",
                "openjiuwen.service.middleware.session-store.type", "none",
                "openjiuwen.service.middleware.object-storage.type", "none",
                "openjiuwen.service.middleware.vector-store.type", "none",
                "openjiuwen.service.middleware.redis.default.host", "redis.local",
                "openjiuwen.service.middleware.redis.default.encrypted-password", "pwd");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
                .orElseGet(MiddlewareProperties::new);

        assertThat(properties.getCheckpointer().getType()).isEqualTo("redis");
        assertThat(properties.getSessionStore().getType()).isEqualTo("none");
        assertThat(properties.getObjectStorage().getType()).isEqualTo("none");
        assertThat(properties.getVectorStore().getType()).isEqualTo("none");
        assertThat(properties.getRedis().get("default").getHost()).isEqualTo("redis.local");
        assertThat(properties.getRedis().get("default").getEncryptedPassword()).isEqualTo("pwd");
    }

    @Test
    void bindsRedisEndpointTypeAndClusterNodes() {
        Map<String, Object> source = Map.of("openjiuwen.service.middleware.checkpointer.type", "redis",
                "openjiuwen.service.middleware.checkpointer.redis-ref", "cluster",
                "openjiuwen.service.middleware.redis.cluster.type", "cluster",
                "openjiuwen.service.middleware.redis.cluster.nodes[0]", "10.10.1.11:6379",
                "openjiuwen.service.middleware.redis.cluster.nodes[1]", "10.10.1.12:6379",
                "openjiuwen.service.middleware.redis.cluster.database", "2");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
                .orElseGet(MiddlewareProperties::new);

        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("cluster");
        assertThat(endpoint.getType()).isEqualTo("cluster");
        assertThat(endpoint.getNodes()).containsExactly("10.10.1.11:6379", "10.10.1.12:6379");
        assertThat(endpoint.getDatabase()).isEqualTo(2);
    }

    @Test
    void defaultsRedisEndpointTypeToStandaloneAndNodesToEmptyList() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();

        assertThat(endpoint.getType()).isEqualTo("standalone");
        assertThat(endpoint.getNodes()).isEqualTo(List.of());
    }

    @Test
    void defaultsCheckpointerTtlToSevenDays() {
        assertThat(new MiddlewareProperties().getCheckpointer().getTtlSeconds()).isEqualTo(604800L);
    }

    @Test
    void bindsCheckpointerTtlSeconds() {
        Map<String, String> source = Map.of("openjiuwen.service.middleware.checkpointer.ttl-seconds", "3600");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
                .orElseGet(MiddlewareProperties::new);

        assertThat(properties.getCheckpointer().getTtlSeconds()).isEqualTo(3600L);
    }

    @Test
    void memoryDefaultsAreDisabled() {
        MiddlewareProperties properties = new MiddlewareProperties();

        assertThat(properties.getMemory().isEnabled()).isFalse();
        assertThat(properties.getMemory().getProvider()).isEqualTo("mem0");
        assertThat(properties.getMemory().getEndpoint()).isEqualTo("https://api.mem0.ai");
        assertThat(properties.getMemory().getEncryptedApiKey()).isEmpty();
        assertThat(properties.getMemory().getUserId()).isEmpty();
        assertThat(properties.getMemory().isRequestScopedSession()).isFalse();
        assertThat(properties.getMemory().isRerank()).isFalse();
        assertThat(properties.getMemory().getTimeoutMs()).isEqualTo(3000);
        assertThat(properties.getMemory().getRetry().getMax()).isZero();
        assertThat(properties.getMemory().getCircuitBreaker().isEnabled()).isFalse();
        assertThat(properties.getMemory().getAudit().isEnabled()).isTrue();
    }

    @Test
    void bindsMemoryGovernanceAndScope() {
        Map<String, String> source = new java.util.HashMap<>();
        source.put("openjiuwen.service.middleware.memory.enabled", "true");
        source.put("openjiuwen.service.middleware.memory.provider", "mem0");
        source.put("openjiuwen.service.middleware.memory.endpoint", "https://mem0.example");
        source.put("openjiuwen.service.middleware.memory.encrypted-api-key", "enc:key");
        source.put("openjiuwen.service.middleware.memory.user-id", "u1");
        source.put("openjiuwen.service.middleware.memory.request-scoped-session", "true");
        source.put("openjiuwen.service.middleware.memory.rerank", "true");
        source.put("openjiuwen.service.middleware.memory.timeout-ms", "3000");
        source.put("openjiuwen.service.middleware.memory.retry.max", "2");
        source.put("openjiuwen.service.middleware.memory.retry.backoff-ms", "200");
        source.put("openjiuwen.service.middleware.memory.circuit-breaker.enabled", "true");
        source.put("openjiuwen.service.middleware.memory.circuit-breaker.failure-threshold", "5");
        source.put("openjiuwen.service.middleware.memory.circuit-breaker.reset-timeout-ms", "120000");
        source.put("openjiuwen.service.middleware.memory.audit.enabled", "true");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source)).bind(
                "openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
            .orElseGet(MiddlewareProperties::new);

        MiddlewareProperties.Memory memory = properties.getMemory();
        assertThat(memory.isEnabled()).isTrue();
        assertThat(memory.getEndpoint()).isEqualTo("https://mem0.example");
        assertThat(memory.getEncryptedApiKey()).isEqualTo("enc:key");
        assertThat(memory.getUserId()).isEqualTo("u1");
        assertThat(memory.isRequestScopedSession()).isTrue();
        assertThat(memory.isRerank()).isTrue();
        assertThat(memory.getTimeoutMs()).isEqualTo(3000);
        assertThat(memory.getRetry().getMax()).isEqualTo(2);
        assertThat(memory.getRetry().getBackoffMs()).isEqualTo(200L);
        assertThat(memory.getCircuitBreaker().isEnabled()).isTrue();
        assertThat(memory.getCircuitBreaker().getFailureThreshold()).isEqualTo(5);
        assertThat(memory.getCircuitBreaker().getResetTimeoutMs()).isEqualTo(120000L);
        assertThat(memory.getAudit().isEnabled()).isTrue();
    }

    @Test
    void memoryRejectsInvalidTimeoutOnBinding() {
        assertThatThrownBy(() -> bindMemory(Map.of("openjiuwen.service.middleware.memory.timeout-ms", "0")))
            .hasRootCauseMessage("memory.timeout-ms must be greater than zero");
    }

    @Test
    void memoryRejectsInvalidRetryOnBinding() {
        assertThatThrownBy(() -> bindMemory(Map.of("openjiuwen.service.middleware.memory.retry.max", "-1")))
            .hasRootCauseMessage("retry.max must be greater than or equal to zero");
        assertThatThrownBy(() -> bindMemory(Map.of("openjiuwen.service.middleware.memory.retry.backoff-ms", "-1")))
            .hasRootCauseMessage("retry.backoff-ms must be greater than or equal to zero");
    }

    @Test
    void memoryRejectsInvalidCircuitBreakerOnBinding() {
        assertThatThrownBy(
            () -> bindMemory(Map.of("openjiuwen.service.middleware.memory.circuit-breaker.failure-threshold", "0")))
            .hasRootCauseMessage("circuit-breaker.failure-threshold must be greater than zero");
        assertThatThrownBy(
            () -> bindMemory(Map.of("openjiuwen.service.middleware.memory.circuit-breaker.reset-timeout-ms", "0")))
            .hasRootCauseMessage("circuit-breaker.reset-timeout-ms must be greater than zero");
    }

    private static MiddlewareProperties bindMemory(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bind(
                "openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
            .orElseGet(MiddlewareProperties::new);
    }
}
