/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;

/**
 * Redis-backed {@link TaskStore} using the same Redis connection as the Checkpointer middleware. Task keys carry a
 * 7-day TTL.
 *
 * <p>
 * Backed by a thread-safe {@link JedisPool}: the A2A request handler, the event-bus processor and the orchestrator all
 * touch the task store concurrently, so each operation borrows its own connection and returns it (try-with-resources).
 * A single shared {@link Jedis} would interleave commands on one socket and corrupt the RESP protocol stream.
 *
 * @since 0.1.0
 */
public class RedisTaskStore implements TaskStore {
    private static final Logger log = LoggerFactory.getLogger(RedisTaskStore.class);
    private static final String KEY_PREFIX = "a2a:task:";
    private static final int TTL_SECONDS = 604800; // 7 days

    // Reuse the SDK's configured Gson: it carries the TypeAdapters for Task's polymorphic Part,
    // OffsetDateTime, and the StreamingEventKind hierarchy. A bare new Gson() reflects into
    // java.time.OffsetDateTime and fails on JDK 17+ ("module java.base does not opens java.time").
    private static final Gson GSON = JsonUtil.OBJECT_MAPPER;

    private final JedisPool jedisPool;

    public RedisTaskStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void save(Task task, boolean isOverwrite) {
        // Upsert unconditionally to match the SDK's reference InMemoryTaskStore, which ignores the
        // isOverwrite flag and always put()s. The event-bus processor re-saves the same task on every
        // state transition with isOverwrite=false; treating that as "fail if exists" breaks the flow.
        String key = KEY_PREFIX + task.id();
        byte[] data = GSON.toJson(task).getBytes(StandardCharsets.UTF_8);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key.getBytes(StandardCharsets.UTF_8), TTL_SECONDS, data);
        }
    }

    @Override
    public Task get(String taskId) {
        String key = KEY_PREFIX + taskId;
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] data = jedis.get(key.getBytes(StandardCharsets.UTF_8));
            if (data == null) {
                return null;
            }
            return GSON.fromJson(new String(data, StandardCharsets.UTF_8), Task.class);
        }
    }

    @Override
    public void delete(String taskId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(KEY_PREFIX + taskId);
        }
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        List<Task> result = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                var scanResult = jedis.scan(cursor, new ScanParams().match(KEY_PREFIX + "*").count(100));
                for (String key : scanResult.getResult()) {
                    byte[] data = jedis.get(key.getBytes(StandardCharsets.UTF_8));
                    if (data == null) {
                        continue;
                    }
                    Task t = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Task.class);
                    if (t != null && matches(t, params)) {
                        result.add(t);
                    }
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return new ListTasksResult(result, result.size(), result.size(), null);
    }

    private boolean matches(Task t, ListTasksParams params) {
        if (params.contextId() != null && !params.contextId().isEmpty() && !params.contextId().equals(t.contextId())) {
            return false;
        }
        if (params.status() != null && !params.status().equals(t.status().state())) {
            return false;
        }
        return true;
    }
}
