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

        assertThat(RedisConnectionAssembler.buildRedisUrl(endpoint, "plain-pass"))
                .isEqualTo("redis://:plain-pass@redis.example:6380/2");
    }

    @Test
    void buildsConnectionMapFromProperties() {
        MiddlewareProperties properties = new MiddlewareProperties();
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.local");
        endpoint.setEncryptedPassword("ENC");
        properties.getRedis().put("default", endpoint);

        int[] scene = new int[] {CredentialSceneType.UNKNOWN};
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

        assertThat(RedisConnectionAssembler.resolveEndpointType(endpoint))
                .isEqualTo(RedisConnectionAssembler.TYPE_STANDALONE);
    }

    @Test
    void rejectsUnsupportedEndpointType() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("sentinel");

        assertThatThrownBy(() -> RedisConnectionAssembler.resolveEndpointType(endpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported openjiuwen.service.middleware.redis endpoint type: sentinel");
    }

    @Test
    void requiresClusterNodesButIgnoresClusterDatabase() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");
        endpoint.setNodes(List.of("10.10.1.11:6379", "10.10.1.12:6379"));
        endpoint.setDatabase(2);

        assertThat(RedisConnectionAssembler.clusterNodes(endpoint)).containsExactly("10.10.1.11:6379",
                "10.10.1.12:6379");
    }

    @Test
    void requiresAtLeastOneClusterNode() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");

        assertThatThrownBy(() -> RedisConnectionAssembler.clusterNodes(endpoint))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodes");
    }

    @Test
    void rejectsMalformedClusterNode() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("cluster");
        endpoint.setNodes(List.of("10.10.1.11"));

        assertThatThrownBy(() -> RedisConnectionAssembler.clusterNodes(endpoint))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host:port");
    }

    @Test
    void buildsStandaloneAndClusterSafeSummariesWithoutSecrets() {
        MiddlewareProperties.RedisEndpoint standalone = new MiddlewareProperties.RedisEndpoint();
        standalone.setHost("redis.example");
        standalone.setPort(6380);
        standalone.setDatabase(2);
        standalone.setTimeoutMs(1500);
        standalone.setEncryptedPassword("ENC(secret)");

        assertThat(RedisConnectionAssembler.safeSummary("default", standalone))
                .contains("ref=default", "type=standalone", "host=redis.example", "port=6380", "database=2",
                        "timeoutMs=1500", "passwordConfigured=true")
                .doesNotContain("ENC(secret)");

        MiddlewareProperties.RedisEndpoint cluster = new MiddlewareProperties.RedisEndpoint();
        cluster.setType("cluster");
        cluster.setNodes(List.of("10.10.1.11:6379", "10.10.1.12:6379"));
        cluster.setDatabase(2);
        cluster.setEncryptedPassword("ENC(cluster-secret)");

        assertThat(RedisConnectionAssembler.safeSummary("cluster", cluster))
                .contains("ref=cluster", "type=cluster", "nodes=2", "databaseIgnored=2", "passwordConfigured=true")
                .doesNotContain("ENC(cluster-secret)");
    }
}
