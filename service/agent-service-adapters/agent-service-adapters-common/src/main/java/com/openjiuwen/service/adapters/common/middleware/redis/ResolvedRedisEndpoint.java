/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import java.util.List;

/**
 * Immutable, validated Redis endpoint consumed by connection adapters.
 *
 * <p>The raw configuration is normalized once by {@link RedisConnectionAssembler}; downstream code must not
 * reinterpret missing values or introduce connection defaults independently.
 *
 * @since 0.1.0
 */
public final class ResolvedRedisEndpoint {
    private final String ref;

    private final String type;

    private final String host;

    private final int port;

    private final List<String> nodes;

    private final int database;

    private final int timeoutMs;

    private final String encryptedPassword;

    ResolvedRedisEndpoint(String ref, String type, String host, int port, List<String> nodes, int database,
            int timeoutMs, String encryptedPassword) {
        this.ref = ref;
        this.type = type;
        this.host = host;
        this.port = port;
        this.nodes = List.copyOf(nodes);
        this.database = database;
        this.timeoutMs = timeoutMs;
        this.encryptedPassword = encryptedPassword;
    }

    public String getRef() {
        return ref;
    }

    public String getType() {
        return type;
    }

    /**
     * Returns whether this endpoint uses Redis Cluster topology.
     *
     * @return {@code true} for a cluster endpoint
     */
    public boolean isCluster() {
        return RedisConnectionAssembler.TYPE_CLUSTER.equals(type);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public List<String> getNodes() {
        return nodes;
    }

    public int getDatabase() {
        return database;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }
}
