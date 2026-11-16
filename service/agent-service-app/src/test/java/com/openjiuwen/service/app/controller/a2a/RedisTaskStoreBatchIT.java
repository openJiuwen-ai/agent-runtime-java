/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.middleware.redis.JedisClusterRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.spec.hosting.ScopedRuntimeRedisClient;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in real Redis checks. Supply runtime.redis.batch.it=true, runtime.redis.it.host,
 * runtime.redis.it.port (standalone), and runtime.redis.it.cluster-port (cluster seed).
 * Only unique test namespaces are touched; the databases are never flushed.
 *
 * @since 0.1.4
 */
@EnabledIfSystemProperty(named = "runtime.redis.batch.it", matches = "true")
class RedisTaskStoreBatchIT {
    private static final String READY = "a2a:task-context:v1:ready";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesBackfillAndTtlsThroughHostedScope(boolean isCluster) {
        try (UnifiedJedis jedis = jedis(isCluster)) {
            RuntimeRedisClient root = client(jedis);
            String prefix = prefix();
            RuntimeRedisClient scoped = new ScopedRuntimeRedisClient(root, prefix);
            RedisTaskStore store = new RedisTaskStore(scoped, 3600L);
            try {
                Task legacy = task("legacy", "旧会话");
                scoped.setex("a2a:task:legacy".getBytes(StandardCharsets.UTF_8), 3600L,
                        JsonUtil.OBJECT_MAPPER.toJson(legacy).getBytes(StandardCharsets.UTF_8));
                store.save(task("new", "新会话"), false);
                assertThat(scoped.exists(READY)).isFalse();
                assertThat(store.list(ListTasksParams.builder().contextId("旧会话").build()).tasks())
                        .extracting(Task::id).containsExactly("legacy");
                assertThat(scoped.exists(READY)).isTrue();
                scoped.expire(READY, 10L);

                Task completed = task("new", "新会话");
                store.save(completed, false);

                assertThat(store.get("new")).isEqualTo(completed);
                assertThat(jedis.ttl(prefix + READY)).isBetween(3590L, 3600L);
                assertThat(jedis.ttl(prefix + "a2a:task:new")).isBetween(3590L, 3600L);
                assertThat(store.list(ListTasksParams.builder().contextId("新会话").build()).tasks())
                        .extracting(Task::id).containsExactly("new");
                scoped.del(READY);
                store.save(task("another", "新会话"), false);
                assertThat(scoped.exists(READY)).isFalse();
            } finally {
                cleanup(scoped);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supportsConcurrentSavesWithIndependentConnections(boolean isCluster) throws Exception {
        try (UnifiedJedis jedis = jedis(isCluster)) {
            RuntimeRedisClient scoped = new ScopedRuntimeRedisClient(client(jedis), prefix());
            RedisTaskStore store = new RedisTaskStore(scoped, 3600L);
            var executor = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(40), new ThreadPoolExecutor.AbortPolicy());
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < 40; index++) {
                    String id = "task-" + index;
                    futures.add(executor.submit(() -> {
                        Task task = task(id, "shared-context");
                        store.save(task, false);
                        assertThat(store.get(id)).isEqualTo(task);
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
                assertThat(store.list(ListTasksParams.builder().contextId("shared-context").pageSize(100).build())
                        .tasks()).hasSize(40);
            } finally {
                executor.shutdownNow();
                cleanup(scoped);
            }
        }
    }

    private UnifiedJedis jedis(boolean isCluster) {
        String host = System.getProperty("runtime.redis.it.host", "127.0.0.1");
        return isCluster ? new JedisCluster(Set.of(new HostAndPort(host,
                Integer.getInteger("runtime.redis.it.cluster-port", 16391))))
                : new JedisPooled(host, Integer.getInteger("runtime.redis.it.port", 16390));
    }

    private RuntimeRedisClient client(UnifiedJedis jedis) {
        if (jedis instanceof JedisCluster cluster) {
            return new JedisClusterRuntimeRedisClient(cluster);
        }
        if (jedis instanceof JedisPooled pooled) {
            return new JedisPooledRuntimeRedisClient(pooled);
        }
        throw new IllegalArgumentException("Unsupported Redis test client");
    }

    private String prefix() {
        return "runtime:batch-it:" + UUID.randomUUID() + ":";
    }

    private Task task(String id, String context) {
        return Task.builder().id(id).contextId(context).status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }

    private void cleanup(RuntimeRedisClient scoped) {
        List<String> keys = scoped.scanIter("*");
        if (!keys.isEmpty()) {
            scoped.del(keys.toArray(String[]::new));
        }
    }
}
