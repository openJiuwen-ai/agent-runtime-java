/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tests the in-memory runtime Redis client command semantics.
 *
 * @since 0.1.3
 */
class InMemoryRuntimeRedisClientTest {
    @Test
    void storesAndReadsStringsAcrossTextAndBinaryKeys() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        assertThat(client.set("k1", "v1")).isEqualTo("OK");
        assertThat(client.get("k1")).isEqualTo("v1");
        assertThat(client.get("k1".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(client.setex("k2", 60L, "v2")).isEqualTo("OK");
        assertThat(client.exists("k2")).isTrue();
        assertThat(client.setnx("k2", "other")).isZero();
        assertThat(client.setnx("k3", "third")).isEqualTo(1L);
        assertThat(client.mget("k1", "k2", "missing")).containsExactly("v1", "v2", null);
    }

    @Test
    void roundTripsBinaryValuesThroughBinaryKeys() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        byte[] key = {0x01, 0x02};
        byte[] value = {0x0A, 0x0B, 0x0C};
        assertThat(client.set(key, value)).isEqualTo("OK");
        assertThat(client.get(key)).isEqualTo(value);
        assertThat(client.exists(key)).isTrue();
        assertThat(client.del(key)).isEqualTo(1L);
        assertThat(client.exists(key)).isFalse();
    }

    @Test
    void expireDeletesImmediatelyForNonPositiveTtl() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        client.set("gone", "value");
        assertThat(client.expire("gone", 0L)).isEqualTo(1L);
        assertThat(client.get("gone")).isNull();
        assertThat(client.expire("missing", 60L)).isZero();
    }

    @Test
    void deletesKeysAcrossNamespacesAndCountsRemovals() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        client.set("str", "v");
        client.hset("hash", "f", "v");
        client.sadd("set", "m");
        assertThat(client.del("str", "hash", "missing")).isEqualTo(2L);
        assertThat(client.exists("set")).isTrue();
    }

    @Test
    void managesHashFieldsWithAtomicIncrement() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        assertThat(client.hset("h", "f1", "a")).isEqualTo(1L);
        assertThat(client.hset("h", "f1", "b")).isZero();
        assertThat(client.hget("h", "f1")).isEqualTo("b");
        assertThat(client.hgetAll("h")).isEqualTo(Map.of("f1", "b"));
        assertThat(client.hincrBy("h", "count", 5L)).isEqualTo(5L);
        assertThat(client.hincrBy("h", "count", -2L)).isEqualTo(3L);
        assertThat(client.hget("h", "count")).isEqualTo("3");
        assertThat(client.hdel("h", "f1", "count")).isEqualTo(2L);
        assertThat(client.hgetAll("h")).isEmpty();
        assertThat(client.exists("h")).isFalse();
    }

    @Test
    void managesSetMembershipAndDropsEmptyKeys() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        assertThat(client.sadd("s", "m1", "m2", "m2")).isEqualTo(2L);
        assertThat(client.sismember("s", "m1")).isTrue();
        assertThat(client.sismember("s", "m9")).isFalse();
        assertThat(client.srem("s", "m1", "m9")).isEqualTo(1L);
        assertThat(client.srem("s", "m2")).isEqualTo(1L);
        assertThat(client.exists("s")).isFalse();
    }

    @Test
    void hincrByAccumulatesConcurrentIncrementsAtomically() throws InterruptedException {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        int taskCount = 4;
        int incrementsPerTask = 250;
        ThreadPoolExecutor executor = newFixedExecutor(taskCount);
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (int task = 0; task < taskCount; task++) {
                executor.execute(() -> {
                    await(start);
                    for (int i = 0; i < incrementsPerTask; i++) {
                        client.hincrBy("counter", "calls", 1L);
                    }
                });
            }
            start.countDown();
        } finally {
            executor.shutdown();
        }
        assertThat(executor.awaitTermination(30L, TimeUnit.SECONDS)).isTrue();
        assertThat(client.hget("counter", "calls"))
                .isEqualTo(String.valueOf(taskCount * incrementsPerTask));
    }

    @Test
    void scansKeysByGlobPatternAcrossNamespaces() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        client.set("task:1", "a");
        client.set("task:2", "b");
        client.set("other:1", "c");
        client.hset("task:3", "f", "v");
        assertThat(client.scanIter("task:*")).containsExactlyInAnyOrder("task:1", "task:2", "task:3");
        assertThat(client.scanIter("task:?")).containsExactlyInAnyOrder("task:1", "task:2", "task:3");
        assertThat(client.scanIter("*:1")).containsExactlyInAnyOrder("task:1", "other:1");
    }

    @Test
    void evalFailsLoudlyInsteadOfDegrading() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        assertThatThrownBy(() -> client.eval("return 1", List.of("k"), "arg"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot evaluate Lua scripts");
    }

    @Test
    void closeClearsAllStoredState() {
        InMemoryRuntimeRedisClient client = new InMemoryRuntimeRedisClient();
        client.set("k", "v");
        client.hset("h", "f", "v");
        client.sadd("s", "m");
        client.close();
        assertThat(client.exists("k")).isFalse();
        assertThat(client.hgetAll("h")).isEmpty();
        assertThat(client.sismember("s", "m")).isFalse();
    }

    private static ThreadPoolExecutor newFixedExecutor(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                    thread.setName("redis-inmemory-hincrby-test");
                    return thread;
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
