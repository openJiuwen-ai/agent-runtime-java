package com.openjiuwen.a2a_service.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.a2a_service.common.Constants;
import com.openjiuwen.a2a_service.common.RedisClient;
import com.openjiuwen.a2a_service.common.RedisTaskStore;
import io.a2a.spec.TaskState;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * user_router — 用户入口路由（Versatile 平台定制格式）。
 *
 * 暴露端点：
 *   POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
 *
 * Task 管理：
 *   首轮：从 Redis 查询 session:{convId}:a2a_task_id；不存在则新建 taskId 并写入 Redis。
 *   续轮：从 Redis 取 taskId → TaskStore 读取 Task → 判断是否为 INPUT_REQUIRED 续轮。
 */
@RestController
public class UserRouter {

    private static final Logger logger = LoggerFactory.getLogger(UserRouter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int REQUEST_TTL = 1800;
    private static final String CONV_TASK_KEY_TEMPLATE = "session:%s:a2a_task_id";
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Executor executor;
    private final RedisClient redis;

    public UserRouter(Executor executor, RedisClient redis) {
        this.executor = executor;
        this.redis = redis;
    }

    /**
     * 提取查询文本。
     */
    @SuppressWarnings("unchecked")
    private String extractQuery(Map<String, Object> body) {
        if (body.get("input") instanceof Map input) {
            Object q = input.get("query");
            if (q instanceof String && !((String) q).isEmpty()) {
                return (String) q;
            }
        }
        if (body.get("custom_data") instanceof Map customData) {
            if (customData.get("inputs") instanceof Map inputs) {
                Object q = inputs.get("query");
                if (q instanceof String && !((String) q).isEmpty()) {
                    return (String) q;
                }
            }
        }
        return "";
    }

    /**
     * Versatile 格式入口。
     */
    @PostMapping("/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}")
    public ResponseEntity<?> dispatch(
            @PathVariable String project_id,
            @PathVariable String agent_id,
            @PathVariable String conversation_id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        String userQuery = extractQuery(body);
        boolean streamMode = (boolean) body.getOrDefault("stream", true);
        String traceId = UUID.randomUUID().toString();

        logger.info("[Router] conv={} stream={} query={} trace={}",
                conversation_id, streamMode,
                userQuery.length() > 80 ? userQuery.substring(0, 80) : userQuery, traceId);

        // 缓存首轮请求头和请求体
        String requestKey = Constants.sessionRequestKey(conversation_id);
        if (redis.getJsonAsMap(requestKey).isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name, request.getHeader(name));
            }
            redis.setJson(requestKey, Map.of("headers", headers, "body", body), REQUEST_TTL);
        }

        // ── Task 管理：convId → taskId 映射 ─────────────────────────────────
        String convTaskKey = String.format(CONV_TASK_KEY_TEMPLATE, conversation_id);
        Optional<String> taskIdOpt = redis.get(convTaskKey);
        String taskId;
        RedisTaskStore.TaskInfo currentTask = null;

        if (taskIdOpt.isPresent()) {
            taskId = taskIdOpt.get();
            Optional<RedisTaskStore.TaskInfo> stored = executor.getTaskStore().get(taskId);
            if (stored.isPresent()) {
                currentTask = stored.get();
                // 上轮已完成 → 原子重置
                if (currentTask.getState() == TaskState.TASK_STATE_COMPLETED) {
                    String newTaskId = UUID.randomUUID().toString();
                    redis.delete(convTaskKey);
                    boolean wasSet = redis.setNx(convTaskKey, newTaskId, REQUEST_TTL);
                    taskId = wasSet ? newTaskId : redis.get(convTaskKey).orElse(newTaskId);
                    currentTask = null;
                    logger.debug("[Router] 上轮已完成，新建 taskId={} for conv={}", taskId, conversation_id);
                }
            }
        } else {
            // 首轮：SET NX 原子创建
            String newTaskId = UUID.randomUUID().toString();
            boolean wasSet = redis.setNx(convTaskKey, newTaskId, REQUEST_TTL);
            if (wasSet) {
                taskId = newTaskId;
                logger.debug("[Router] 新建 taskId={} for conv={}", taskId, conversation_id);
            } else {
                taskId = redis.get(convTaskKey).orElse(newTaskId);
                currentTask = executor.getTaskStore().get(taskId).orElse(null);
                logger.debug("[Router] 并发首轮，复用 taskId={} for conv={}", taskId, conversation_id);
            }
        }

        String finalTaskId = taskId;
        RedisTaskStore.TaskInfo finalCurrentTask = currentTask;

        // 获取请求头
        Map<String, Object> originalHeaders = new HashMap<>();
        java.util.Enumeration<String> hNames = request.getHeaderNames();
        while (hNames.hasMoreElements()) {
            String name = hNames.nextElement();
            originalHeaders.put(name, request.getHeader(name));
        }

        if (streamMode) {
            SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时

            executorService.submit(() -> {
                try {
                    executor.execute(
                            finalTaskId, conversation_id, finalCurrentTask,
                            userQuery, originalHeaders, body,
                            event -> {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .data(objectMapper.writeValueAsString(event)));
                                } catch (IOException e) {
                                    logger.warn("[Router] SSE 发送异常", e);
                                }
                            }
                    );
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (Exception e) {
                    logger.error("[Router] execute 异常", e);
                    try {
                        Map<String, Object> failEvent = Map.of(
                                "type", "status_update",
                                "taskId", finalTaskId,
                                "contextId", conversation_id,
                                "state", "FAILED"
                        );
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(failEvent)));
                    } catch (IOException ignored) {}
                    emitter.completeWithError(e);
                }
            });

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "keep-alive")
                    .header("X-Accel-Buffering", "no")
                    .body(emitter);

        } else {
            // 非流式
            List<Map<String, Object>> collected = new ArrayList<>();
            executor.execute(
                    finalTaskId, conversation_id, finalCurrentTask,
                    userQuery, originalHeaders, body,
                    collected::add
            );

            String answer = "";
            for (Map<String, Object> event : collected) {
                if ("completed".equals(event.get("type"))) {
                    Object content = event.get("content");
                    if (content != null) {
                        answer = content.toString();
                    }
                }
            }

            return ResponseEntity.ok(Map.of("success", true, "answer", answer));
        }
    }
}
