/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-agnostic Redis connection description from middleware properties.
 */
public final class RedisConnectionAssembler {

    private RedisConnectionAssembler() {
    }

    public static MiddlewareProperties.RedisEndpoint resolveEndpoint(MiddlewareProperties properties, String redisRef) {
        String ref = redisRef;
        if (ref == null || ref.isBlank()) {
            ref = "default";
        }
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get(ref);
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis." + ref + " is required for redis middleware");
        }
        return endpoint;
    }

    public static Map<String, Object> buildConnectionMap(MiddlewareProperties.RedisEndpoint endpoint,
                                                         String decryptedPassword) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", buildRedisUrl(endpoint, decryptedPassword));
        return connection;
    }

    public static Map<String, Object> buildConnectionMap(MiddlewareProperties properties,
                                                         String redisRef,
                                                         CredentialDecryptor decryptor) {
        MiddlewareProperties.RedisEndpoint endpoint = resolveEndpoint(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword());
        return buildConnectionMap(endpoint, password);
    }

    public static String buildRedisUrl(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        String host = endpoint.getHost() != null ? endpoint.getHost().trim() : "localhost";
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 6379;
        int database = endpoint.getDatabase() >= 0 ? endpoint.getDatabase() : 0;
        if (password == null || password.isBlank()) {
            return "redis://" + host + ":" + port + "/" + database;
        }
        String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
        return "redis://:" + encodedPassword + "@" + host + ":" + port + "/" + database;
    }
}
