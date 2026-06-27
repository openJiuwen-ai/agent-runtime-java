/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;

/**
 * Redis-backed {@link TaskStore} using the same Redis connection as the Checkpointer middleware. Task keys carry a
 * 7-day TTL.
 *
 * @since 0.1.0
 */
public class RedisTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskStore.class);
    private static final String KEY_PREFIX = "a2a:task:";
    private static final int TTL_SECONDS = 604800; // 7 days
    private static final Gson GSON = new Gson();

    private final Jedis jedis;

    public RedisTaskStore(Jedis jedis) {
        this.jedis = jedis;
    }

    @Override
    public void save(Task task, boolean overwrite) {
        String key = KEY_PREFIX + task.id();
        if (!overwrite && jedis.exists(key)) {
            throw new IllegalStateException("Task already exists: " + task.id());
        }
        byte[] data = GSON.toJson(task).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jedis.setex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), TTL_SECONDS, data);
    }

    @Override
    public Task get(String taskId) {
        String key = KEY_PREFIX + taskId;
        byte[] data = jedis.get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (data == null) {
            return null;
        }
        return GSON.fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8), Task.class);
    }

    @Override
    public void delete(String taskId) {
        jedis.del(KEY_PREFIX + taskId);
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        List<Task> result = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            var scanResult = jedis.scan(cursor, new ScanParams().match(KEY_PREFIX + "*").count(100));
            for (String key : scanResult.getResult()) {
                byte[] data = jedis.get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (data == null)
                    continue;
                Task t = GSON.fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8), Task.class);
                if (t != null && matches(t, params)) {
                    result.add(t);
                }
            }
            cursor = scanResult.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
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
