/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.ConnectionPool;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link RuntimeRedisClient} backed by Jedis' Redis Cluster command client.
 *
 * @since 0.1.0
 */
public class JedisClusterRuntimeRedisClient extends UnifiedJedisRuntimeRedisClient {
    private final JedisCluster cluster;

    /**
     * Creates a runtime Redis cluster client.
     *
     * @param delegate Jedis cluster client
     */
    public JedisClusterRuntimeRedisClient(JedisCluster delegate) {
        super(delegate);
        this.cluster = delegate;
    }

    @Override
    public List<String> scanIter(String pattern) {
        if (cluster.getClusterNodes().isEmpty()) {
            return super.scanIter(pattern);
        }
        Set<String> keys = new LinkedHashSet<>();
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        for (ConnectionPool pool : cluster.getClusterNodes().values()) {
            scanNode(pool, params, keys);
        }
        return new ArrayList<>(keys);
    }

    private void scanNode(ConnectionPool pool, ScanParams params, Set<String> keys) {
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            try (var connection = pool.getResource()) {
                UnifiedJedis nodeClient = new UnifiedJedis(connection);
                var result = nodeClient.scan(cursor, params);
                keys.addAll(result.getResult());
                cursor = result.getCursor();
            }
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }
}
