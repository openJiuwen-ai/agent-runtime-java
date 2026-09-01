/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.exceptions.JedisException;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskPersistenceException;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.util.PageToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis-backed {@link TaskStore} using the same Redis connection as the
 * Checkpointer middleware. Task keys carry the configured state-cache TTL.
 * <p>
 * Backed by a thread-safe {@link RuntimeRedisClient}: the A2A request handler, the
 * event-bus processor and the orchestrator all
 * touch the task store concurrently, so the configured runtime Redis implementation
 * must be safe for singleton use.
 * <p>
 * Redis driver failures (e.g. connection drops) are translated into
 * {@link TaskPersistenceException}, so callers only observe the failure contract
 * declared by {@link TaskStore} and never driver-specific runtime exceptions.
 *
 * @since 0.1.0
 */
public class RedisTaskStore implements TaskStore {
    private static final Logger log = LoggerFactory.getLogger(RedisTaskStore.class);

    private static final String KEY_PREFIX = "a2a:task:";

    private static final String CONTEXT_INDEX_PREFIX = "a2a:task-context:v1:";

    private static final String CONTEXT_INDEX_READY_KEY = CONTEXT_INDEX_PREFIX + "ready";

    private static final String INDEX_VALUE = "1";

    private static final int MGET_BATCH_SIZE = 100;

    private static final Comparator<Task> TASK_ORDER = Comparator.comparing(RedisTaskStore::taskTimestamp).reversed()
            .thenComparing(Task::id);

    // Reuse the SDK's configured Gson: it carries the TypeAdapters for Task's
    // polymorphic Part,
    // reflects into
    // java.time.OffsetDateTime and fails on JDK 17+ ("module java.base does not
    // opens java.time").
    private static final Gson GSON = JsonUtil.OBJECT_MAPPER;

    private final RuntimeRedisClient redisClient;

    private final long ttlSeconds;

    public RedisTaskStore(RuntimeRedisClient redisClient, long ttlSeconds) {
        this.redisClient = redisClient;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be greater than 0");
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void save(Task task, boolean isOverwrite) {
        // Upsert unconditionally to match the SDK's reference InMemoryTaskStore, which
        // ignores the
        // isOverwrite flag and always put()s. The event-bus processor re-saves the same
        // task on every
        // state transition with isOverwrite=false; treating that as "fail if exists"
        // breaks the flow.
        String key = KEY_PREFIX + task.id();
        byte[] data = GSON.toJson(task).getBytes(StandardCharsets.UTF_8);
        call("save", task.id(), () -> {
            writeContextIndex(task);
            redisClient.setex(key.getBytes(StandardCharsets.UTF_8), ttlSeconds, data);
            if (redisClient.exists(CONTEXT_INDEX_READY_KEY)) {
                redisClient.setex(CONTEXT_INDEX_READY_KEY, ttlSeconds, INDEX_VALUE);
            }
            return null;
        });
    }

    @Override
    public Task get(String taskId) {
        String key = KEY_PREFIX + taskId;
        byte[] data = call("get", taskId, () -> redisClient.get(key.getBytes(StandardCharsets.UTF_8)));
        if (data == null) {
            return null;
        }
        return GSON.fromJson(new String(data, StandardCharsets.UTF_8), Task.class);
    }

    @Override
    public void delete(String taskId) {
        String taskKey = KEY_PREFIX + taskId;
        Optional<Task> task = deserialize(taskKey,
                call("get", taskId, () -> redisClient.get(taskKey.getBytes(StandardCharsets.UTF_8))));
        if (task.isEmpty()) {
            call("delete", taskId, () -> redisClient.del(taskKey));
            return;
        }
        call("delete", taskId, () -> redisClient.del(taskKey, contextIndexKey(task.get().contextId(), taskId)));
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        List<Task> allFilteredTasks = call("list", null, () -> loadCandidates(params)).stream()
                .filter(task -> matches(task, params))
                .sorted(TASK_ORDER).toList();
        int totalSize = allFilteredTasks.size();
        int startIndex = pageStart(allFilteredTasks, PageToken.fromString(params.pageToken()));
        int endIndex = Math.min(startIndex + params.getEffectivePageSize(), totalSize);
        List<Task> pageTasks = allFilteredTasks.subList(startIndex, endIndex).stream()
                .map(task -> transformTask(task, params.getEffectiveHistoryLength(), params.shouldIncludeArtifacts()))
                .toList();
        String nextPageToken = null;
        if (endIndex < totalSize) {
            Task lastTask = allFilteredTasks.get(endIndex - 1);
            nextPageToken = new PageToken(taskTimestamp(lastTask), lastTask.id()).toString();
        }
        return new ListTasksResult(pageTasks, totalSize, pageTasks.size(), nextPageToken);
    }

    /**
     * Runs a Redis command, translating driver-level access failures (e.g.
     * connection drops) into {@link TaskPersistenceException} so callers only
     * observe the failure contract declared by the {@link TaskStore} interface.
     *
     * @param operation the Redis operation name used in the failure message
     * @param taskId the task the command belongs to; {@code null} for store-wide
     *        operations such as list
     * @param command the Redis command to execute
     * @param <T> the command result type
     * @return the command result
     */
    private <T> T call(String operation, String taskId, Supplier<T> command) {
        try {
            return command.get();
        } catch (JedisException e) {
            throw new TaskPersistenceException(taskId,
                    "Redis " + operation + " failed: " + e.getMessage(), e);
        }
    }

    private List<Task> loadCandidates(ListTasksParams params) {
        List<String> taskKeys;
        if (hasContextFilter(params)) {
            ensureContextIndex();
            String indexPrefix = contextIndexPrefix(params.contextId());
            taskKeys = redisClient.scanIter(indexPrefix + "*").stream()
                    .map(indexKey -> KEY_PREFIX + indexKey.substring(indexPrefix.length())).toList();
        } else {
            taskKeys = redisClient.scanIter(KEY_PREFIX + "*");
        }
        return readTasks(taskKeys);
    }

    private List<Task> readTasks(List<String> taskKeys) {
        List<Task> tasks = new ArrayList<>();
        for (int start = 0; start < taskKeys.size(); start += MGET_BATCH_SIZE) {
            List<String> batchKeys = taskKeys.subList(start, Math.min(start + MGET_BATCH_SIZE, taskKeys.size()));
            List<Object> values = redisClient.mget(batchKeys.toArray(String[]::new));
            if (values.size() != batchKeys.size()) {
                throw new IllegalStateException(
                        "Redis mget returned " + values.size() + " values for " + batchKeys.size() + " task keys");
            }
            for (int index = 0; index < values.size(); index++) {
                deserialize(batchKeys.get(index), values.get(index)).ifPresent(tasks::add);
            }
        }
        return tasks;
    }

    private Optional<Task> deserialize(String key, Object data) {
        if (data == null) {
            return Optional.empty();
        }
        String json;
        if (data instanceof byte[] bytes) {
            json = new String(bytes, StandardCharsets.UTF_8);
        } else if (data instanceof String text) {
            json = text;
        } else {
            log.warn("Skipping task entry with unsupported value type key={} type={}", key, data.getClass().getName());
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(GSON.fromJson(json, Task.class));
        } catch (JsonParseException ex) {
            log.warn("Skipping malformed task entry key={}: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private void ensureContextIndex() {
        if (redisClient.exists(CONTEXT_INDEX_READY_KEY)) {
            return;
        }
        for (Task task : readTasks(redisClient.scanIter(KEY_PREFIX + "*"))) {
            writeContextIndex(task);
        }
        redisClient.setex(CONTEXT_INDEX_READY_KEY, ttlSeconds, INDEX_VALUE);
    }

    private void writeContextIndex(Task task) {
        redisClient.setex(contextIndexKey(task.contextId(), task.id()), ttlSeconds, INDEX_VALUE);
    }

    private static String contextIndexKey(String contextId, String taskId) {
        return contextIndexPrefix(contextId) + taskId;
    }

    private static String contextIndexPrefix(String contextId) {
        String encodedContext = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(contextId.getBytes(StandardCharsets.UTF_8));
        return CONTEXT_INDEX_PREFIX + encodedContext + ":";
    }

    private static boolean hasContextFilter(ListTasksParams params) {
        return params.contextId() != null && !params.contextId().isEmpty();
    }

    private static boolean matches(Task t, ListTasksParams params) {
        if (params.contextId() != null && !params.contextId().isEmpty() && !params.contextId().equals(t.contextId())) {
            return false;
        }
        if (params.status() != null && !params.status().equals(t.status().state())) {
            return false;
        }
        return params.statusTimestampAfter() == null
                || t.status().timestamp().toInstant().isAfter(params.statusTimestampAfter());
    }

    private static Instant taskTimestamp(Task task) {
        return task.status().timestamp().toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static int pageStart(List<Task> tasks, PageToken pageToken) {
        if (pageToken == null) {
            return 0;
        }
        int left = 0;
        int right = tasks.size();
        while (left < right) {
            int middle = left + (right - left) / 2;
            Task task = tasks.get(middle);
            Instant timestamp = taskTimestamp(task);
            int timestampComparison = timestamp.compareTo(pageToken.timestamp());
            if (timestampComparison < 0 || timestampComparison == 0 && task.id().compareTo(pageToken.id()) > 0) {
                right = middle;
            } else {
                left = middle + 1;
            }
        }
        return left;
    }

    private static Task transformTask(Task task, int historyLength, boolean shouldIncludeArtifacts) {
        List<Message> history = transformHistory(task.history(), historyLength);
        List<Artifact> artifacts = shouldIncludeArtifacts ? task.artifacts() : List.of();
        if (history == task.history() && artifacts == task.artifacts()) {
            return task;
        }
        return Task.builder(task).history(history).artifacts(artifacts).build();
    }

    private static List<Message> transformHistory(List<Message> history, int historyLength) {
        if (historyLength == 0) {
            return List.of();
        }
        if (history.size() > historyLength) {
            return history.subList(history.size() - historyLength, history.size());
        }
        return history;
    }
}
