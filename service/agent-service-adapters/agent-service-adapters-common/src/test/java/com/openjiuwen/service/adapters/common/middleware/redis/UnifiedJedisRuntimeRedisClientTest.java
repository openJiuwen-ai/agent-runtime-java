/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;

import redis.clients.jedis.UnifiedJedis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests that the Jedis-backed client delegates the structured command and eval batch unchanged.
 *
 * @since 0.1.3
 */
class UnifiedJedisRuntimeRedisClientTest {
    @Test
    void delegatesStructuredCommandsOneToOne() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        UnifiedJedisRuntimeRedisClient client = new UnifiedJedisRuntimeRedisClient(delegate);

        assertThat(client.hset("key", "field", "value")).isEqualTo(1L);
        assertThat(client.hget("key", "field")).isEqualTo("value");
        assertThat(client.hdel("key", "f1", "f2")).isEqualTo(2L);
        assertThat(client.hgetAll("key")).isEqualTo(Map.of("f", "v"));
        assertThat(client.sadd("key", "m1", "m2")).isEqualTo(2L);
        assertThat(client.sismember("key", "m1")).isTrue();
        assertThat(client.srem("key", "m1")).isEqualTo(1L);
        assertThat(client.hincrBy("key", "field", 5L)).isEqualTo(5L);

        assertThat(delegate.calls).containsExactly(
                "hset:key:field:value",
                "hget:key:field",
                "hdel:key:[f1, f2]",
                "hgetAll:key",
                "sadd:key:[m1, m2]",
                "sismember:key:m1",
                "srem:key:[m1]",
                "hincrBy:key:field:5");
    }

    @Test
    void delegatesEvalWithVarargsCollectedIntoAList() {
        RecordingUnifiedJedis delegate = new RecordingUnifiedJedis();
        UnifiedJedisRuntimeRedisClient client = new UnifiedJedisRuntimeRedisClient(delegate);

        assertThat(client.eval("return 1", List.of("key"), "a", "b")).isEqualTo(1L);
        assertThat(client.eval("return 2", List.of(), "solo")).isEqualTo(2L);

        assertThat(delegate.evalCalls).containsExactly(
                List.of("return 1", List.of("key"), List.of("a", "b")),
                List.of("return 2", List.of(), List.of("solo")));
    }

    private static final class RecordingUnifiedJedis extends UnifiedJedis {
        private final List<String> calls = new ArrayList<>();

        private final List<List<Object>> evalCalls = new ArrayList<>();

        @Override
        public long hset(String key, String field, String value) {
            calls.add("hset:" + key + ":" + field + ":" + value);
            return 1L;
        }

        @Override
        public String hget(String key, String field) {
            calls.add("hget:" + key + ":" + field);
            return "value";
        }

        @Override
        public long hdel(String key, String... fields) {
            calls.add("hdel:" + key + ":" + Arrays.toString(fields));
            return 2L;
        }

        @Override
        public Map<String, String> hgetAll(String key) {
            calls.add("hgetAll:" + key);
            return Map.of("f", "v");
        }

        @Override
        public long sadd(String key, String... members) {
            calls.add("sadd:" + key + ":" + Arrays.toString(members));
            return 2L;
        }

        @Override
        public boolean sismember(String key, String member) {
            calls.add("sismember:" + key + ":" + member);
            return true;
        }

        @Override
        public long srem(String key, String... members) {
            calls.add("srem:" + key + ":" + Arrays.toString(members));
            return 1L;
        }

        @Override
        public long hincrBy(String key, String field, long delta) {
            calls.add("hincrBy:" + key + ":" + field + ":" + delta);
            return 5L;
        }

        @Override
        public Object eval(String script, List<String> keys, List<String> args) {
            evalCalls.add(List.of(script, keys, args));
            return (long) evalCalls.size();
        }
    }
}
