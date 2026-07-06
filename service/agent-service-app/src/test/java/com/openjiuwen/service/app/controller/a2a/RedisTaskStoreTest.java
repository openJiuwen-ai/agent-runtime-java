/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests Redis-backed A2A task store behavior through the runtime Redis SPI.
 *
 * @since 0.1.0
 */
class RedisTaskStoreTest {
    @Test
    void storesTasksThroughRuntimeRedisClient() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient);
        Task task = task("task-1", "ctx-1", TaskState.TASK_STATE_WORKING);

        store.save(task, false);

        assertThat(store.get("task-1")).isEqualTo(task);
        assertThat(redisClient.ttlByKey()).containsEntry("a2a:task:task-1", 604800L);
    }

    @Test
    void listsAndDeletesTasksThroughRuntimeRedisClient() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient);
        store.save(task("task-1", "ctx-1", TaskState.TASK_STATE_WORKING), false);
        store.save(task("task-2", "ctx-2", TaskState.TASK_STATE_COMPLETED), false);

        ListTasksResult listed = store.list(new ListTasksParams("ctx-1", null, null, null, null, null, null, null));
        store.delete("task-1");

        assertThat(listed.tasks()).extracting(Task::id).containsExactly("task-1");
        assertThat(store.get("task-1")).isNull();
        assertThat(store.get("task-2")).isNotNull();
    }

    private Task task(String id, String contextId, TaskState state) {
        return Task.builder().id(id).contextId(contextId).status(new TaskStatus(state)).build();
    }

    private static final class InMemoryRuntimeRedisClient implements RuntimeRedisClient {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private final Map<String, Long> ttlByKey = new LinkedHashMap<>();

        Map<String, Long> ttlByKey() {
            return ttlByKey;
        }

        @Override
        public Object get(String key) {
            byte[] value = values.get(key);
            return value == null ? null : value;
        }

        @Override
        public byte[] get(byte[] key) {
            return values.get(new String(key, StandardCharsets.UTF_8));
        }

        @Override
        public String set(String key, String value) {
            values.put(key, value.getBytes(StandardCharsets.UTF_8));
            return "OK";
        }

        @Override
        public String set(String key, byte[] value) {
            values.put(key, value);
            return "OK";
        }

        @Override
        public String set(byte[] key, byte[] value) {
            values.put(new String(key, StandardCharsets.UTF_8), value);
            return "OK";
        }

        @Override
        public String setex(String key, long seconds, String value) {
            ttlByKey.put(key, seconds);
            return set(key, value);
        }

        @Override
        public String setex(byte[] key, long seconds, byte[] value) {
            String textKey = new String(key, StandardCharsets.UTF_8);
            ttlByKey.put(textKey, seconds);
            values.put(textKey, value);
            return "OK";
        }

        @Override
        public long setnx(String key, String value) {
            if (values.containsKey(key)) {
                return 0;
            }
            set(key, value);
            return 1;
        }

        @Override
        public long setnx(byte[] key, byte[] value) {
            String textKey = new String(key, StandardCharsets.UTF_8);
            if (values.containsKey(textKey)) {
                return 0;
            }
            values.put(textKey, value);
            return 1;
        }

        @Override
        public long del(String... keys) {
            return Arrays.stream(keys).filter(key -> values.remove(key) != null).count();
        }

        @Override
        public long del(byte[]... keys) {
            return Arrays.stream(keys).filter(key -> values.remove(new String(key, StandardCharsets.UTF_8)) != null)
                    .count();
        }

        @Override
        public boolean exists(String key) {
            return values.containsKey(key);
        }

        @Override
        public boolean exists(byte[] key) {
            return values.containsKey(new String(key, StandardCharsets.UTF_8));
        }

        @Override
        public long expire(String key, long seconds) {
            ttlByKey.put(key, seconds);
            return values.containsKey(key) ? 1 : 0;
        }

        @Override
        public long expire(byte[] key, long seconds) {
            return expire(new String(key, StandardCharsets.UTF_8), seconds);
        }

        @Override
        public List<Object> mget(String... keys) {
            return Arrays.stream(keys).map(this::get).toList();
        }

        @Override
        public List<String> scanIter(String pattern) {
            String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
            return values.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }
    }
}
