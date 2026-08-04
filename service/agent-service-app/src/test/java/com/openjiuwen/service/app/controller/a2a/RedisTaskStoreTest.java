/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
        RedisTaskStore store = new RedisTaskStore(redisClient, 3600L);
        Task task = task("task-1", "ctx-1", TaskState.TASK_STATE_WORKING);

        store.save(task, false);

        assertThat(store.get("task-1")).isEqualTo(task);
        assertThat(redisClient.ttlByKey()).containsEntry("a2a:task:task-1", 3600L);
    }

    @Test
    void listsAndDeletesTasksThroughRuntimeRedisClient() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient, 604800L);
        store.save(task("task-1", "ctx-1", TaskState.TASK_STATE_WORKING), false);
        store.save(task("task-2", "ctx-2", TaskState.TASK_STATE_COMPLETED), false);

        ListTasksResult listed = store.list(new ListTasksParams("ctx-1", null, null, null, null, null, null, null));
        store.delete("task-1");

        assertThat(listed.tasks()).extracting(Task::id).containsExactly("task-1");
        assertThat(store.get("task-1")).isNull();
        assertThat(store.get("task-2")).isNotNull();
    }

    @Test
    void skipsMalformedTaskEntriesWhenListing() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient, 604800L);
        store.save(task("task-1", "ctx-1", TaskState.TASK_STATE_WORKING), false);
        redisClient.values.put("a2a:task:broken", "not-json".getBytes(StandardCharsets.UTF_8));

        ListTasksResult listed = store.list(ListTasksParams.builder().contextId("ctx-1").build());

        assertThat(listed.tasks()).extracting(Task::id).containsExactly("task-1");
    }

    @Test
    void backfillsLegacyContextIndexAndThenAvoidsFullTaskScan() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient, 604800L);
        Task legacyTask = task("legacy-task", "legacy-context", TaskState.TASK_STATE_WORKING);
        redisClient.values.put("a2a:task:legacy-task",
                JsonUtil.OBJECT_MAPPER.toJson(legacyTask).getBytes(StandardCharsets.UTF_8));

        ListTasksResult first = store.list(ListTasksParams.builder().contextId("legacy-context").build());
        redisClient.scanPatterns.clear();
        ListTasksResult second = store.list(ListTasksParams.builder().contextId("legacy-context").build());

        assertThat(first.tasks()).extracting(Task::id).containsExactly("legacy-task");
        assertThat(second.tasks()).extracting(Task::id).containsExactly("legacy-task");
        assertThat(redisClient.scanPatterns).singleElement().asString().startsWith("a2a:task-context:v1:");
    }

    @Test
    void appliesPaginationHistoryAndArtifactProjection() {
        InMemoryRuntimeRedisClient redisClient = new InMemoryRuntimeRedisClient();
        RedisTaskStore store = new RedisTaskStore(redisClient, 604800L);
        store.save(taskWithPayload("task-1", OffsetDateTime.parse("2026-07-27T01:00:00Z")), false);
        store.save(taskWithPayload("task-2", OffsetDateTime.parse("2026-07-27T02:00:00Z")), false);
        store.save(taskWithPayload("task-3", OffsetDateTime.parse("2026-07-27T03:00:00Z")), false);
        ListTasksParams firstPageParams = ListTasksParams.builder().contextId("ctx-1").pageSize(2).historyLength(1)
                .includeArtifacts(false).build();

        ListTasksResult first = store.list(firstPageParams);
        ListTasksResult second = store.list(ListTasksParams.builder().contextId("ctx-1").pageSize(2)
                .pageToken(first.nextPageToken()).historyLength(1).includeArtifacts(false).build());

        assertThat(first.totalSize()).isEqualTo(3);
        assertThat(first.tasks()).extracting(Task::id).containsExactly("task-3", "task-2");
        assertThat(first.tasks()).allSatisfy(task -> {
            assertThat(task.history()).hasSize(1);
            assertThat(task.artifacts()).isEmpty();
        });
        assertThat(first.nextPageToken()).isNotBlank();
        assertThat(second.tasks()).extracting(Task::id).containsExactly("task-1");
        assertThat(second.nextPageToken()).isNull();
    }

    private Task task(String id, String contextId, TaskState state) {
        return Task.builder().id(id).contextId(contextId).status(new TaskStatus(state)).build();
    }

    private Task taskWithPayload(String id, OffsetDateTime timestamp) {
        Message first = Message.builder().role(Message.Role.ROLE_USER).parts(List.of(new TextPart("first"))).build();
        Message second = Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart("second"))).build();
        Artifact artifact = Artifact.builder().artifactId("artifact-" + id).parts(List.of(new TextPart("result")))
                .build();
        return Task.builder().id(id).contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING, null, timestamp)).history(List.of(first, second))
                .artifacts(List.of(artifact)).build();
    }

    private static final class InMemoryRuntimeRedisClient implements RuntimeRedisClient {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private final Map<String, Long> ttlByKey = new LinkedHashMap<>();
        private final List<String> scanPatterns = new ArrayList<>();

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
            scanPatterns.add(pattern);
            String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
            return values.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }
    }
}
