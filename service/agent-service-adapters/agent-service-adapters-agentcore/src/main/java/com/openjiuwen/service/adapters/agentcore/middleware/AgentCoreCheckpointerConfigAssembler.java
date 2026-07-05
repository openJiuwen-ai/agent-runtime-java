/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisConnectionAssembler;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisJedisClientFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds AgentCore {@code RunnerConfig.checkpointerConfig} maps from service
 * middleware properties.
 *
 * @since 0.1.0
 */
public final class AgentCoreCheckpointerConfigAssembler {
    /** In-memory checkpointer type token. */
    public static final String TYPE_IN_MEMORY = "in_memory";

    /** Redis checkpointer type token. */
    public static final String TYPE_REDIS = "redis";

    private AgentCoreCheckpointerConfigAssembler() {
    }

    /**
     * Builds the Core checkpointer configuration map from middleware properties.
     *
     * @param properties the middleware properties
     * @param decryptor the credential decryptor
     * @return the checkpointer configuration map
     */
    public static Map<String, Object> build(MiddlewareProperties properties, CredentialDecryptor decryptor) {
        String type = normalizeType(properties.getCheckpointer().getType());
        if (TYPE_IN_MEMORY.equals(type)) {
            return Map.of("type", TYPE_IN_MEMORY, "conf", Map.of());
        }
        if (TYPE_REDIS.equals(type)) {
            return Map.of("type", TYPE_REDIS, "conf", buildRedisConf(properties, decryptor));
        }
        throw new IllegalArgumentException(
            "Unsupported openjiuwen.service.middleware.checkpointer.type: " + type + " (supported: in_memory, redis)");
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TYPE_IN_MEMORY;
        }
        return type.trim();
    }

    private static Map<String, Object> buildRedisConf(MiddlewareProperties properties, CredentialDecryptor decryptor) {
        String redisRef = properties.getCheckpointer().getRedisRef();
        MiddlewareProperties.RedisEndpoint endpoint = RedisConnectionAssembler.resolveEndpoint(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword());

        Map<String, Object> connection = new HashMap<>(RedisConnectionAssembler.buildConnectionMap(endpoint, password));
        connection.put("redis_client", RedisJedisClientFactory.createPooled(endpoint, password));

        Map<String, Object> conf = new HashMap<>();
        conf.put("connection", connection);
        return conf;
    }
}
