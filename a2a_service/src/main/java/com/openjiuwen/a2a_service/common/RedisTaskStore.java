package com.openjiuwen.a2a_service.common;

import io.a2a.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Redis-backed A2A TaskStore.
 *
 * Task 序列化为 JSON 存入 Redis，key 格式：a2a:task:{task_id}。
 * TTL 复用 redisSessionTtl 配置（秒），默认 1800 s。
 */
public class RedisTaskStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisTaskStore.class);
    private static final String KEY_PREFIX = "a2a:task:";

    private final RedisClient redis;
    private final int ttl;

    public RedisTaskStore(RedisClient redis, int ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * 保存 task 的核心信息到 Redis（仅保存必要字段用于续轮判断）。
     */
    public void save(TaskInfo taskInfo) {
        String key = KEY_PREFIX + taskInfo.getTaskId();
        redis.setJson(key, taskInfo, ttl);
        logger.debug("[TaskStore] save task={} state={}", taskInfo.getTaskId(), taskInfo.getState());
    }

    /**
     * 从 Redis 获取 task 信息。
     */
    public Optional<TaskInfo> get(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return Optional.empty();
        }
        return redis.getJsonAsMap(KEY_PREFIX + taskId)
                .map(map -> {
                    String id = (String) map.get("taskId");
                    String contextId = (String) map.get("contextId");
                    String stateStr = (String) map.get("state");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
                    return new TaskInfo(id, contextId, TaskState.valueOf(stateStr), metadata != null ? metadata : new HashMap<>());
                });
    }

    /**
     * 删除 task。
     */
    public void delete(String taskId) {
        redis.delete(KEY_PREFIX + taskId);
        logger.debug("[TaskStore] delete task={}", taskId);
    }

    /**
     * Task 信息 DTO（不依赖 protobuf）。
     */
    public static class TaskInfo {
        private final String taskId;
        private final String contextId;
        private final TaskState state;
        private final Map<String, Object> metadata;

        public TaskInfo(String taskId, String contextId, TaskState state, Map<String, Object> metadata) {
            this.taskId = taskId;
            this.contextId = contextId;
            this.state = state;
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }

        public String getTaskId() { return taskId; }
        public String getContextId() { return contextId; }
        public TaskState getState() { return state; }
        public Map<String, Object> getMetadata() { return metadata; }

        public TaskInfo withState(TaskState newState) {
            return new TaskInfo(this.taskId, this.contextId, newState, this.metadata);
        }

        public TaskInfo withMetadata(String key, Object value) {
            Map<String, Object> newMeta = new HashMap<>(this.metadata);
            newMeta.put(key, value);
            return new TaskInfo(this.taskId, this.contextId, this.state, newMeta);
        }
    }
}
