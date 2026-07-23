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
 * Resolves middleware properties into validated, engine-agnostic Redis connection descriptions.
 *
 * @since 0.1.0
 */
public final class RedisConnectionAssembler {
    /** Standalone Redis endpoint type. */
    public static final String TYPE_STANDALONE = "standalone";

    /** Redis Cluster endpoint type. */
    public static final String TYPE_CLUSTER = "cluster";

    private static final int DEFAULT_PORT = 6379;

    private static final int DEFAULT_TIMEOUT_MS = 3000;

    private RedisConnectionAssembler() {
    }

    /**
     * Resolves and validates a named Redis endpoint from middleware properties.
     *
     * @param properties the middleware properties
     * @param redisRef the redis endpoint reference name
     * @return an immutable resolved endpoint
     */
    public static ResolvedRedisEndpoint resolve(MiddlewareProperties properties, String redisRef) {
        String ref = normalizedRef(redisRef);
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get(ref);
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis." + ref + " is required for redis middleware");
        }
        return resolveEndpoint(ref, endpoint);
    }

    /**
     * Resolves and validates a named Redis endpoint while preserving the original raw-endpoint API.
     *
     * @param properties the middleware properties
     * @param redisRef the redis endpoint reference name
     * @return the raw endpoint after validation
     */
    public static MiddlewareProperties.RedisEndpoint resolveEndpoint(MiddlewareProperties properties, String redisRef) {
        ResolvedRedisEndpoint resolved = resolve(properties, redisRef);
        return properties.getRedis().get(resolved.getRef());
    }

    /**
     * Resolves and validates a Redis endpoint using the supplied reference in validation messages.
     *
     * @param redisRef the redis endpoint reference name
     * @param endpoint the raw endpoint properties
     * @return an immutable resolved endpoint
     */
    public static ResolvedRedisEndpoint resolveEndpoint(String redisRef, MiddlewareProperties.RedisEndpoint endpoint) {
        String ref = normalizedRef(redisRef);
        String type = resolveEndpointType(endpoint);
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : DEFAULT_PORT;
        int database = endpoint.getDatabase() >= 0 ? endpoint.getDatabase() : 0;
        int timeoutMs = endpoint.getTimeoutMs() > 0 ? endpoint.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
        String encryptedPassword = endpoint.getEncryptedPassword() != null ? endpoint.getEncryptedPassword() : "";
        if (TYPE_CLUSTER.equals(type)) {
            return new ResolvedRedisEndpoint(ref, type, null, port, clusterNodes(ref, endpoint), database, timeoutMs,
                    encryptedPassword);
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis." + ref + ".host is required when type=standalone");
        }
        return new ResolvedRedisEndpoint(ref, type, host.trim(), port, List.of(), database, timeoutMs,
                encryptedPassword);
    }

    /**
     * Builds a non-sensitive endpoint summary for diagnostics.
     *
     * @param endpoint the resolved endpoint
     * @return a safe summary that does not contain passwords or ciphertext
     */
    public static String safeSummary(ResolvedRedisEndpoint endpoint) {
        boolean isPasswordConfigured = !endpoint.getEncryptedPassword().isBlank();
        if (endpoint.isCluster()) {
            StringBuilder summary = new StringBuilder().append("ref=").append(endpoint.getRef()).append(", type=")
                    .append(endpoint.getType()).append(", nodes=").append(endpoint.getNodes().size())
                    .append(", timeoutMs=").append(endpoint.getTimeoutMs()).append(", passwordConfigured=")
                    .append(isPasswordConfigured);
            if (endpoint.getDatabase() != 0) {
                summary.append(", databaseIgnored=").append(endpoint.getDatabase());
            }
            return summary.toString();
        }
        return "ref=" + endpoint.getRef() + ", type=" + endpoint.getType() + ", host=" + endpoint.getHost() + ", port="
                + endpoint.getPort() + ", database=" + endpoint.getDatabase() + ", timeoutMs=" + endpoint.getTimeoutMs()
                + ", passwordConfigured=" + isPasswordConfigured;
    }

    /**
     * Builds a safe summary through the centralized endpoint resolver.
     *
     * @param redisRef the endpoint reference
     * @param endpoint the raw endpoint properties
     * @return a non-sensitive endpoint summary
     */
    public static String safeSummary(String redisRef, MiddlewareProperties.RedisEndpoint endpoint) {
        return safeSummary(resolveEndpoint(redisRef, endpoint));
    }

    /**
     * Builds a connection map from a resolved Redis endpoint.
     *
     * @param endpoint the resolved endpoint
     * @param decryptedPassword the decrypted password
     * @return the connection map
     */
    public static Map<String, Object> buildConnectionMap(ResolvedRedisEndpoint endpoint, String decryptedPassword) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", buildRedisUrl(endpoint, decryptedPassword));
        return connection;
    }

    /**
     * Builds a connection map through the centralized endpoint resolver.
     *
     * @param endpoint the raw endpoint properties
     * @param decryptedPassword the decrypted password
     * @return the connection map
     */
    public static Map<String, Object> buildConnectionMap(MiddlewareProperties.RedisEndpoint endpoint,
            String decryptedPassword) {
        return buildConnectionMap(resolveEndpoint("default", endpoint), decryptedPassword);
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
        ResolvedRedisEndpoint endpoint = resolve(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        return buildConnectionMap(endpoint, password);
    }

    /**
     * Builds a Redis URL from resolved endpoint settings and password.
     *
     * <p>For a Cluster endpoint the first validated node is used only as the connection-map seed. Runtime commands
     * continue to use the topology-aware {@code RuntimeRedisClient} supplied alongside this URL.
     *
     * @param endpoint the resolved endpoint
     * @param password the decrypted password, may be blank
     * @return the Redis URL string
     */
    public static String buildRedisUrl(ResolvedRedisEndpoint endpoint, String password) {
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        int database = endpoint.getDatabase();
        if (endpoint.isCluster()) {
            NodeAddress seed = parseNode(endpoint.getNodes().get(0));
            host = seed.host();
            port = seed.port();
            database = 0;
        }
        if (password == null || password.isBlank()) {
            return "redis://" + host + ":" + port + "/" + database;
        }
        String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
        return "redis://:" + encodedPassword + "@" + host + ":" + port + "/" + database;
    }

    /**
     * Builds a Redis URL through the centralized endpoint resolver.
     *
     * @param endpoint the raw endpoint properties
     * @param password the decrypted password, may be blank
     * @return the Redis URL string
     */
    public static String buildRedisUrl(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        return buildRedisUrl(resolveEndpoint("default", endpoint), password);
    }

    /**
     * Resolves and validates the endpoint type.
     *
     * @param endpoint the raw endpoint properties
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
     * @param endpoint the raw endpoint properties
     * @return normalized seed node strings
     */
    public static List<String> clusterNodes(MiddlewareProperties.RedisEndpoint endpoint) {
        return clusterNodes("<ref>", endpoint);
    }

    private static List<String> clusterNodes(String ref, MiddlewareProperties.RedisEndpoint endpoint) {
        List<String> nodes = new ArrayList<>();
        for (String rawNode : endpoint.getNodes()) {
            if (rawNode == null || rawNode.isBlank()) {
                continue;
            }
            String node = rawNode.trim();
            validateClusterNode(ref, node);
            nodes.add(node);
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis." + ref + ".nodes is required when type=cluster");
        }
        return nodes;
    }

    private static void validateClusterNode(String ref, String node) {
        try {
            NodeAddress address = parseNode(node);
            if (address.host().isBlank() || address.port() <= 0) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.middleware.redis." + ref + ".nodes entries must use host:port format: " + node,
                    ex);
        }
    }

    private static NodeAddress parseNode(String node) {
        int colon = node.lastIndexOf(':');
        if (colon <= 0 || colon == node.length() - 1) {
            throw new IllegalArgumentException("Invalid Redis node: " + node);
        }
        return new NodeAddress(node.substring(0, colon), Integer.parseInt(node.substring(colon + 1)));
    }

    private static String normalizedRef(String redisRef) {
        return redisRef == null || redisRef.isBlank() ? "default" : redisRef.trim();
    }

    private record NodeAddress(String host, int port) {
    }
}
