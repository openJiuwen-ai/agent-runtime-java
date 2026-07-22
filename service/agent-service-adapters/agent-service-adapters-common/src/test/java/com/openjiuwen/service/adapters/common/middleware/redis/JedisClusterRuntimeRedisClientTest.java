/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.util.JedisClusterCRC16;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests topology-aware multi-key commands for Redis Cluster.
 *
 * @since 0.1.0
 */
class JedisClusterRuntimeRedisClientTest {
    @Test
    void mgetGroupsBySlotAndRestoresInputOrderAndMissingValues() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        byte[] valueA = bytes("value-a");
        byte[] valueA2 = bytes("value-a2");
        delegate.put("{slot-a}:1", valueA);
        delegate.put("{slot-a}:2", valueA2);
        JedisClusterRuntimeRedisClient client = new JedisClusterRuntimeRedisClient(delegate);

        List<Object> values = client.mget("{slot-a}:1", "{slot-b}:missing", "{slot-a}:2");

        assertThat(values).containsExactly(valueA, null, valueA2);
        assertThat(delegate.binaryMgetCalls).hasSize(2);
        assertEachCallUsesOneSlot(delegate.binaryMgetCalls);
        assertThat(delegate.binaryMgetCalls.get(0)).hasSize(2);
    }

    @Test
    void textDeleteGroupsBySlotAndAccumulatesDeletedCount() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        delegate.textKeys.addAll(Set.of("{slot-a}:1", "{slot-a}:2", "{slot-b}:1"));
        JedisClusterRuntimeRedisClient client = new JedisClusterRuntimeRedisClient(delegate);

        long deleted = client.del("{slot-a}:1", "{slot-b}:1", "{slot-a}:2", "{slot-c}:missing");

        assertThat(deleted).isEqualTo(3);
        assertThat(delegate.textDelCalls).hasSize(3);
        assertEachTextCallUsesOneSlot(delegate.textDelCalls);
    }

    @Test
    void binaryDeleteUsesRawBytesForSlotAndAccumulatesDeletedCount() {
        byte[] keyA = new byte[]{0, (byte) 0xff, 1};
        byte[] keyB = new byte[]{0, (byte) 0xfe, 2};
        byte[] keyMissing = new byte[]{0, (byte) 0xfd, 3};
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        delegate.binaryKeys.add(fingerprint(keyA));
        delegate.binaryKeys.add(fingerprint(keyB));
        JedisClusterRuntimeRedisClient client = new JedisClusterRuntimeRedisClient(delegate);

        long deleted = client.del(keyA, keyMissing, keyB);

        assertThat(deleted).isEqualTo(2);
        assertEachCallUsesOneSlot(delegate.binaryDelCalls);
        List<byte[]> delegatedKeys = delegate.binaryDelCalls.stream().flatMap(List::stream).toList();
        assertThat(delegatedKeys).anySatisfy(key -> assertThat(key).containsExactly(keyA));
        assertThat(delegatedKeys).anySatisfy(key -> assertThat(key).containsExactly(keyB));
    }

    @Test
    void reportsPartialDeleteProgressWithoutExposingKeys() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        delegate.textKeys.add("{slot-a}:1");
        delegate.failTextDeleteSlot = JedisClusterCRC16.getSlot("{slot-b}:1");
        JedisClusterRuntimeRedisClient client = new JedisClusterRuntimeRedisClient(delegate);

        assertThatThrownBy(() -> client.del("{slot-a}:1", "{slot-b}:1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis Cluster del failed", "completedGroups=1", "totalGroups=2",
                        "deletedBeforeFailure=1")
                .hasMessageNotContaining("{slot-a}").hasMessageNotContaining("{slot-b}")
                .hasCauseInstanceOf(JedisConnectionException.class);
    }

    @Test
    void emptyMultiKeyOperationsDoNotCallRedis() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        JedisClusterRuntimeRedisClient client = new JedisClusterRuntimeRedisClient(delegate);

        assertThat(client.mget()).isEmpty();
        assertThat(client.del(new String[0])).isZero();
        assertThat(client.del(new byte[0][])).isZero();
        assertThat(delegate.binaryMgetCalls).isEmpty();
        assertThat(delegate.textDelCalls).isEmpty();
        assertThat(delegate.binaryDelCalls).isEmpty();
    }

    private static void assertEachTextCallUsesOneSlot(List<List<String>> calls) {
        for (List<String> call : calls) {
            assertThat(call).extracting(JedisClusterCRC16::getSlot).containsOnly(callSlot(call.get(0)));
        }
    }

    private static int callSlot(String key) {
        return JedisClusterCRC16.getSlot(key);
    }

    private static void assertEachCallUsesOneSlot(List<List<byte[]>> calls) {
        for (List<byte[]> call : calls) {
            assertThat(call).extracting(JedisClusterCRC16::getSlot)
                    .containsOnly(JedisClusterCRC16.getSlot(call.get(0)));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String fingerprint(byte[] value) {
        return Arrays.toString(value);
    }

    private static final class RecordingUnifiedJedis extends UnifiedJedis {
        private final Map<String, byte[]> values = new HashMap<>();

        private final Set<String> textKeys = new HashSet<>();

        private final Set<String> binaryKeys = new HashSet<>();

        private final List<List<byte[]>> binaryMgetCalls = new ArrayList<>();

        private final List<List<String>> textDelCalls = new ArrayList<>();

        private final List<List<byte[]>> binaryDelCalls = new ArrayList<>();

        private int failTextDeleteSlot = -1;

        void put(String key, byte[] value) {
            values.put(fingerprint(bytes(key)), value);
        }

        @Override
        public List<byte[]> mget(byte[]... keys) {
            binaryMgetCalls.add(copy(keys));
            return Arrays.stream(keys).map(key -> values.get(fingerprint(key))).toList();
        }

        @Override
        public long del(String... keys) {
            textDelCalls.add(List.of(Arrays.copyOf(keys, keys.length)));
            if (keys.length > 0 && JedisClusterCRC16.getSlot(keys[0]) == failTextDeleteSlot) {
                throw new JedisConnectionException("injected failure");
            }
            return Arrays.stream(keys).filter(textKeys::remove).count();
        }

        @Override
        public long del(byte[]... keys) {
            binaryDelCalls.add(copy(keys));
            return Arrays.stream(keys).map(JedisClusterRuntimeRedisClientTest::fingerprint).filter(binaryKeys::remove)
                    .count();
        }

        @Override
        public void close() {
            // No network resources are created by this recording test double.
        }

        private static List<byte[]> copy(byte[][] keys) {
            return Arrays.stream(keys).map(key -> Arrays.copyOf(key, key.length)).toList();
        }
    }
}
