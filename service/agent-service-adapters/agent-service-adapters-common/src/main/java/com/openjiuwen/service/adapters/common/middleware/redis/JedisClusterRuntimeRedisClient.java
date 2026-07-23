/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.ConnectionPool;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.util.JedisClusterCRC16;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link RuntimeRedisClient} backed by Jedis' Redis Cluster command client.
 *
 * @since 0.1.0
 */
public class JedisClusterRuntimeRedisClient extends UnifiedJedisRuntimeRedisClient {
    private final JedisCluster cluster;

    private final UnifiedJedis commandDelegate;

    /**
     * Creates a runtime Redis cluster client.
     *
     * @param delegate Jedis cluster client
     */
    public JedisClusterRuntimeRedisClient(JedisCluster delegate) {
        this(delegate, delegate);
    }

    JedisClusterRuntimeRedisClient(UnifiedJedis delegate) {
        this(delegate, null);
    }

    private JedisClusterRuntimeRedisClient(UnifiedJedis delegate, JedisCluster cluster) {
        super(delegate);
        this.commandDelegate = delegate;
        this.cluster = cluster;
    }

    @Override
    public List<Object> mget(String... keys) {
        Map<Integer, List<IndexedBinaryKey>> groups = new LinkedHashMap<>();
        for (int index = 0; index < keys.length; index++) {
            byte[] key = toBytes(keys[index]);
            groups.computeIfAbsent(JedisClusterCRC16.getSlot(key), ignored -> new ArrayList<>())
                    .add(new IndexedBinaryKey(index, key));
        }
        List<Object> orderedValues = new ArrayList<>(Collections.nCopies(keys.length, null));
        int completedGroups = 0;
        for (Map.Entry<Integer, List<IndexedBinaryKey>> group : groups.entrySet()) {
            List<IndexedBinaryKey> indexedKeys = group.getValue();
            byte[][] slotKeys = indexedKeys.stream().map(IndexedBinaryKey::key).toArray(byte[][]::new);
            List<byte[]> slotValues;
            try {
                slotValues = commandDelegate.mget(slotKeys);
            } catch (JedisException ex) {
                throw multiKeyFailure("mget", group.getKey(), new OperationProgress(completedGroups, groups.size(), 0L),
                        ex);
            }
            if (slotValues.size() != indexedKeys.size()) {
                IllegalStateException cause = new IllegalStateException("Redis Cluster mget returned "
                        + slotValues.size() + " values for " + indexedKeys.size() + " keys");
                throw multiKeyFailure("mget", group.getKey(), new OperationProgress(completedGroups, groups.size(), 0L),
                        cause);
            }
            for (int valueIndex = 0; valueIndex < slotValues.size(); valueIndex++) {
                orderedValues.set(indexedKeys.get(valueIndex).index(), slotValues.get(valueIndex));
            }
            completedGroups++;
        }
        return orderedValues;
    }

    @Override
    public long del(String... keys) {
        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        for (String key : keys) {
            groups.computeIfAbsent(JedisClusterCRC16.getSlot(key), ignored -> new ArrayList<>()).add(key);
        }
        return deleteTextGroups(groups);
    }

    @Override
    public long del(byte[]... keys) {
        Map<Integer, List<byte[]>> groups = new LinkedHashMap<>();
        for (byte[] key : keys) {
            groups.computeIfAbsent(JedisClusterCRC16.getSlot(key), ignored -> new ArrayList<>()).add(key);
        }
        return deleteBinaryGroups(groups);
    }

    @Override
    public List<String> scanIter(String pattern) {
        if (cluster == null || cluster.getClusterNodes().isEmpty()) {
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

    private long deleteTextGroups(Map<Integer, List<String>> groups) {
        long deleted = 0L;
        int completedGroups = 0;
        for (Map.Entry<Integer, List<String>> group : groups.entrySet()) {
            try {
                deleted += commandDelegate.del(group.getValue().toArray(String[]::new));
                completedGroups++;
            } catch (JedisException ex) {
                throw multiKeyFailure("del", group.getKey(),
                        new OperationProgress(completedGroups, groups.size(), deleted), ex);
            }
        }
        return deleted;
    }

    private long deleteBinaryGroups(Map<Integer, List<byte[]>> groups) {
        long deleted = 0L;
        int completedGroups = 0;
        for (Map.Entry<Integer, List<byte[]>> group : groups.entrySet()) {
            try {
                deleted += commandDelegate.del(group.getValue().toArray(byte[][]::new));
                completedGroups++;
            } catch (JedisException ex) {
                throw multiKeyFailure("binary del", group.getKey(),
                        new OperationProgress(completedGroups, groups.size(), deleted), ex);
            }
        }
        return deleted;
    }

    private IllegalStateException multiKeyFailure(String operation, int slot, OperationProgress progress,
            RuntimeException cause) {
        String deletedSummary = operation.contains("del") ? ", deletedBeforeFailure=" + progress.deleted() : "";
        return new IllegalStateException(
                "Redis Cluster " + operation + " failed for slot=" + slot + ", completedGroups="
                        + progress.completedGroups() + ", totalGroups=" + progress.totalGroups() + deletedSummary,
                cause);
    }

    private record OperationProgress(int completedGroups, int totalGroups, long deleted) {
    }

    private record IndexedBinaryKey(int index, byte[] key) {
        private IndexedBinaryKey {
            key = Arrays.copyOf(key, key.length);
        }

        @Override
        public byte[] key() {
            return Arrays.copyOf(key, key.length);
        }
    }
}
