/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-agnostic Redis connection description from middleware properties.
 *
 * @since 0.1.0
 */
public final class RedisConnectionAssembler {
    /** Standalone Redis endpoint type. */
    public static final String TYPE_STANDALONE = "standalone";

    /** Redis Cluster endpoint type. */
    public static final String TYPE_CLUSTER = "cluster";

    private RedisConnectionAssembler() {
    }

    /**
     * Resolves a named Redis endpoint from middleware properties.
     *
     * @param properties the middleware properties
     * @param redisRef the redis endpoint reference name
     * @return the resolved Redis endpoint
     */
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

    /**
     * Resolves and validates the endpoint type.
     *
     * @param endpoint the Redis endpoint
     * @return normalized endpoint type
     */
    public static String resolveEndpointType(MiddlewareProperties.RedisEndpoint endpoint) {
        String type = endpoint.getType();
        if (type == null || type.isBlank()) {
            return TYPE_STANDALONE;
        }
        String normalized = type.trim();
        if (TYPE_STANDALONE.equals(normalized) || TYPE_CLUSTER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported openjiuwen.service.middleware.redis endpoint type: "
                + normalized + " (supported: standalone, cluster)");
    }

    /**
     * Returns validated Redis Cluster seed nodes.
     *
     * @param endpoint the Redis endpoint
     * @return cluster seed node strings
     */
    public static List<String> clusterNodes(MiddlewareProperties.RedisEndpoint endpoint) {
        List<String> nodes = new ArrayList<>();
        for (String rawNode : endpoint.getNodes()) {
            if (rawNode == null || rawNode.isBlank()) {
                continue;
            }
            String node = rawNode.trim();
            validateClusterNode(node);
            nodes.add(node);
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis.<ref>.nodes is required for cluster redis middleware");
        }
        return nodes;
    }

    /**
     * Builds a non-sensitive endpoint summary for diagnostics.
     *
     * @param redisRef the redis endpoint reference name
     * @param endpoint the Redis endpoint
     * @return a safe summary that does not contain passwords or ciphertext
     */
    public static String safeSummary(String redisRef, MiddlewareProperties.RedisEndpoint endpoint) {
        String ref = redisRef == null || redisRef.isBlank() ? "default" : redisRef.trim();
        String type = resolveEndpointType(endpoint);
        boolean isPasswordConfigured = endpoint.getEncryptedPassword() != null
                && !endpoint.getEncryptedPassword().isBlank();
        if (TYPE_CLUSTER.equals(type)) {
            StringBuilder summary = new StringBuilder().append("ref=").append(ref).append(", type=").append(type)
                    .append(", nodes=").append(clusterNodes(endpoint).size()).append(", timeoutMs=")
                    .append(timeoutMs(endpoint)).append(", passwordConfigured=").append(isPasswordConfigured);
            if (endpoint.getDatabase() != 0) {
                summary.append(", databaseIgnored=").append(endpoint.getDatabase());
            }
            return summary.toString();
        }
        return "ref=" + ref + ", type=" + type + ", host=" + host(endpoint) + ", port=" + port(endpoint) + ", database="
                + database(endpoint) + ", timeoutMs=" + timeoutMs(endpoint) + ", passwordConfigured="
                + isPasswordConfigured;
    }

    /**
     * Builds a connection map from a resolved Redis endpoint.
     *
     * @param endpoint the Redis endpoint
     * @param decryptedPassword the decrypted password
     * @return the connection map
     */
    public static Map<String, Object> buildConnectionMap(MiddlewareProperties.RedisEndpoint endpoint,
            String decryptedPassword) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", buildRedisUrl(endpoint, decryptedPassword));
        return connection;
    }

    /**
     * Builds a connection map from middleware properties and a Redis reference.
     *
     * @param properties the middleware properties
     * @param redisRef the redis endpoint reference name
     * @param decryptor the credential decryptor
     * @return the connection map
     */
    public static Map<String, Object> buildConnectionMap(MiddlewareProperties properties, String redisRef,
            CredentialDecryptor decryptor) {
        MiddlewareProperties.RedisEndpoint endpoint = resolveEndpoint(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        return buildConnectionMap(endpoint, password);
    }

    /**
     * Builds a Redis URL from endpoint settings and password.
     *
     * @param endpoint the Redis endpoint
     * @param password the decrypted password, may be blank
     * @return the Redis URL string
     */
    public static String buildRedisUrl(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        String host = host(endpoint);
        int port = port(endpoint);
        int database = database(endpoint);
        if (password == null || password.isBlank()) {
            return "redis://" + host + ":" + port + "/" + database;
        }
        String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
        return "redis://:" + encodedPassword + "@" + host + ":" + port + "/" + database;
    }

    private static void validateClusterNode(String node) {
        int colon = node.lastIndexOf(':');
        if (colon <= 0 || colon == node.length() - 1) {
            throw new IllegalArgumentException("Redis cluster node must use host:port format: " + node);
        }
        try {
            int port = Integer.parseInt(node.substring(colon + 1));
            if (port <= 0) {
                throw new IllegalArgumentException("Redis cluster node port must be positive: " + node);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Redis cluster node must use host:port format: " + node, ex);
        }
    }

    private static String host(MiddlewareProperties.RedisEndpoint endpoint) {
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            return "localhost";
        }
        return host.trim();
    }

    private static int port(MiddlewareProperties.RedisEndpoint endpoint) {
        return endpoint.getPort() > 0 ? endpoint.getPort() : 6379;
    }

    private static int database(MiddlewareProperties.RedisEndpoint endpoint) {
        return endpoint.getDatabase() >= 0 ? endpoint.getDatabase() : 0;
    }

    private static int timeoutMs(MiddlewareProperties.RedisEndpoint endpoint) {
        return endpoint.getTimeoutMs() > 0 ? endpoint.getTimeoutMs() : 3000;
    }
}
