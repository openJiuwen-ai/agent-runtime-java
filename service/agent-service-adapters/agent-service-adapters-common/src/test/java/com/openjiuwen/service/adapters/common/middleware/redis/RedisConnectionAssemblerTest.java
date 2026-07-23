/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * RedisConnectionAssemblerTest
 *
 * @since 2026-07-03
 */
class RedisConnectionAssemblerTest {
    @Test
    void buildsRedisUrlWithDecryptedPassword() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.example");
        endpoint.setPort(6380);
        endpoint.setDatabase(2);

        assertThat(RedisConnectionAssembler.buildRedisUrl(resolve("default", endpoint), "plain-pass"))
                .isEqualTo("redis://:plain-pass@redis.example:6380/2");
    }

    @Test
    void buildsConnectionMapFromProperties() {
        MiddlewareProperties properties = new MiddlewareProperties();
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.local");
        endpoint.setEncryptedPassword("ENC");
        properties.getRedis().put("default", endpoint);

        int[] scene = new int[]{CredentialSceneType.UNKNOWN};
        CredentialDecryptor decryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return "secret";
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                scene[0] = sceneType;
                return "secret";
            }
        };

        Map<String, Object> connection = RedisConnectionAssembler.buildConnectionMap(properties, "default", decryptor);
        assertThat(connection.get("url")).asString().contains("redis.local");
        assertThat(connection.get("url")).asString().contains("secret");
        assertThat(scene[0]).isEqualTo(CredentialSceneType.REDIS_PASSWORD);
    }

    @Test
    void resolveEndpointRequiresDefinition() {
        MiddlewareProperties properties = new MiddlewareProperties();
        assertThatThrownBy(() -> RedisConnectionAssembler.resolveEndpoint(properties, "default"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redis.default");
    }

    @Test
    void resolvesBlankEndpointTypeAsStandalone() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType(" ");
        endpoint.setHost(" redis.example ");

        ResolvedRedisEndpoint resolved = resolve("default", endpoint);

        assertThat(resolved.getType()).isEqualTo(RedisConnectionAssembler.TYPE_STANDALONE);
        assertThat(resolved.getHost()).isEqualTo("redis.example");
    }

    @Test
    void rejectsUnsupportedEndpointType() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("sentinel");

        assertThatThrownBy(() -> resolve("default", endpoint)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported openjiuwen.service.middleware.redis endpoint type: sentinel");
    }

    @Test
    void requiresClusterNodesButIgnoresClusterDatabase() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");
        endpoint.setNodes(List.of("10.10.1.11:6379", "10.10.1.12:6379"));
        endpoint.setDatabase(2);

        ResolvedRedisEndpoint resolved = resolve("cluster", endpoint);

        assertThat(resolved.getNodes()).containsExactly("10.10.1.11:6379", "10.10.1.12:6379");
        assertThat(resolved.getDatabase()).isEqualTo(2);
    }

    @Test
    void requiresAtLeastOneClusterNode() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");

        assertThatThrownBy(() -> resolve("cluster", endpoint)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis.cluster.nodes");
    }

    @Test
    void rejectsMalformedClusterNode() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");
        endpoint.setNodes(List.of("10.10.1.11"));

        assertThatThrownBy(() -> resolve("cluster", endpoint)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host:port");
    }

    @Test
    void requiresStandaloneHostWithoutLocalhostFallback() {
        for (String host : new String[]{null, "", "   "}) {
            MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
            endpoint.setHost(host);

            assertThatThrownBy(() -> resolve("primary", endpoint)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("openjiuwen.service.middleware.redis.primary.host is required when type=standalone");
        }
    }

    @Test
    void validatesBeforeDecryptingCredentials() {
        MiddlewareProperties properties = new MiddlewareProperties();
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setEncryptedPassword("ENC(secret)");
        properties.getRedis().put("default", endpoint);
        int[] decryptCalls = new int[1];
        CredentialDecryptor decryptor = ciphertext -> {
            decryptCalls[0]++;
            return "secret";
        };

        assertThatThrownBy(() -> RedisConnectionAssembler.buildConnectionMap(properties, "default", decryptor))
                .hasMessageContaining("redis.default.host");
        assertThat(decryptCalls[0]).isZero();
    }

    @Test
    void appliesConnectionDefaultsOnlyDuringResolution() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.example");
        endpoint.setPort(0);
        endpoint.setDatabase(-1);
        endpoint.setTimeoutMs(0);

        ResolvedRedisEndpoint resolved = resolve("default", endpoint);

        assertThat(resolved.getPort()).isEqualTo(6379);
        assertThat(resolved.getDatabase()).isZero();
        assertThat(resolved.getTimeoutMs()).isEqualTo(3000);
    }

    @Test
    void buildsStandaloneAndClusterSafeSummariesWithoutSecrets() {
        MiddlewareProperties.RedisEndpoint standalone = new MiddlewareProperties.RedisEndpoint();
        standalone.setHost("redis.example");
        standalone.setPort(6380);
        standalone.setDatabase(2);
        standalone.setTimeoutMs(1500);
        standalone.setEncryptedPassword("ENC(secret)");

        assertThat(RedisConnectionAssembler.safeSummary(resolve("default", standalone)))
                .contains("ref=default", "type=standalone", "host=redis.example", "port=6380", "database=2",
                        "timeoutMs=1500", "passwordConfigured=true")
                .doesNotContain("ENC(secret)");

        MiddlewareProperties.RedisEndpoint cluster = new MiddlewareProperties.RedisEndpoint();
        cluster.setType("cluster");
        cluster.setNodes(List.of("10.10.1.11:6379", "10.10.1.12:6379"));
        cluster.setDatabase(2);
        cluster.setEncryptedPassword("ENC(cluster-secret)");

        assertThat(RedisConnectionAssembler.safeSummary(resolve("cluster", cluster)))
                .contains("ref=cluster", "type=cluster", "nodes=2", "databaseIgnored=2", "passwordConfigured=true")
                .doesNotContain("ENC(cluster-secret)");
    }

    @Test
    void usesFirstClusterNodeAsAgentCoreConnectionSeed() {
        MiddlewareProperties.RedisEndpoint cluster = new MiddlewareProperties.RedisEndpoint();
        cluster.setType("cluster");
        cluster.setNodes(List.of("10.10.1.11:6380", "10.10.1.12:6381"));
        cluster.setDatabase(2);

        assertThat(RedisConnectionAssembler.buildRedisUrl(resolve("cluster", cluster), ""))
                .isEqualTo("redis://10.10.1.11:6380/0");
    }

    private static ResolvedRedisEndpoint resolve(String ref, MiddlewareProperties.RedisEndpoint endpoint) {
        return RedisConnectionAssembler.resolveEndpoint(ref, endpoint);
    }
}
