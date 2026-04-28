package com.openjiuwen.a2a_service.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.a2a_service.common.Constants;
import com.openjiuwen.a2a_service.common.ResponseWrapper;
import com.openjiuwen.a2a_service.common.RedisClient;
import com.openjiuwen.a2a_service.common.RedisTaskStore;
import com.openjiuwen.a2a_service.config.Settings;
import io.a2a.spec.TaskState;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String FLOW_CONTROL_TEST_QUERY_PREFIX = "flow-control-test:";
    private static final String ERROR_CODE_RATE_LIMIT_EXCEEDED = "100001";
    private static final String ERROR_MSG_RATE_LIMIT_EXCEEDED = "系统超负载，请在稍后重试";
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Executor executor;
    private final RedisClient redis;
    private final Settings settings;

    public UserRouter(Executor executor, RedisClient redis, Settings settings) {
        this.executor = executor;
        this.redis = redis;
        this.settings = settings;
    }

    /**
     * 提取查询文本。
     */
    @SuppressWarnings("unchecked")
    private String extractQuery(Map<String, Object> body) {
        Object question = body.get("question");
        if (question instanceof String && !((String) question).isEmpty()) {
            return (String) question;
        }
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
    public Object dispatch(
            @PathVariable String project_id,
            @PathVariable String agent_id,
            @PathVariable String conversation_id,
            @RequestBody Object rawBody,
            HttpServletRequest request) {

        String contentType = request.getContentType() != null ? request.getContentType() : "";
        if (!contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.status(415).body(Map.of(
                    "success", false,
                    "error", "unsupported_media_type",
                    "message", "请求数据格式需为 application/json"
            ));
        }

        if (!(rawBody instanceof Map<?, ?> rawMap)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "invalid_body",
                    "message", "请求 body 必须是 JSON 对象（dict）"
            ));
        }

        Map<String, Object> body = castMap(rawMap);

        String userQuery = extractQuery(body);
        if (userQuery == null || userQuery.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "invalid_body",
                    "message", "请求 body 缺少 question、input.query 或 custom_data.inputs.query"
            ));
        }
        boolean streamMode = (boolean) body.getOrDefault("stream", true);
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        logger.info("[Router] conv={} stream={} query={} trace={}",
                conversation_id, streamMode,
                userQuery.length() > 80 ? userQuery.substring(0, 80) : userQuery, traceId);

        if (!checkRateLimit(agent_id, conversation_id)) {
            Map<String, Object> rejection = Map.of(
                    "success", false,
                    "error", ERROR_MSG_RATE_LIMIT_EXCEEDED,
                    "error_code", ERROR_CODE_RATE_LIMIT_EXCEEDED,
                    "conversation_id", conversation_id,
                    "agent_id", agent_id
            );
            if (streamMode) {
                SseEmitter emitter = new SseEmitter(60_000L);
                executorService.submit(() -> {
                    try {
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(rejection)));
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                return emitter;
            }
            return ResponseEntity.status(429).body(rejection);
        }

        // 缓存首轮请求头和请求体
        Map<String, Object> queryParams = extractQueryParams(request);
        String requestKey = Constants.sessionRequestKey(conversation_id);
        if (redis.getJsonAsMap(requestKey).isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name, request.getHeader(name));
            }
            redis.setJson(requestKey, Map.of("headers", headers, "body", body, "params", queryParams), REQUEST_TTL);
        }

        if (userQuery.startsWith(FLOW_CONTROL_TEST_QUERY_PREFIX)) {
            ensureTaskMapping(conversation_id);
            Map<String, Object> probeResponse = Map.of(
                    "success", true,
                    "answer", "flow-control-test-ok",
                    "conversation_id", conversation_id,
                    "agent_id", agent_id
            );
            if (streamMode) {
                SseEmitter emitter = new SseEmitter(60_000L);
                executorService.submit(() -> {
                    try {
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(probeResponse)));
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                return emitter;
            }
            return ResponseEntity.ok(probeResponse);
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
                            userQuery, originalHeaders, body, queryParams,
                            event -> {
                                try {
                                    Map<String, Object> wrapped = wrapEvent(
                                            event, agent_id, conversation_id, startedAt);
                                    emitter.send(SseEmitter.event()
                                            .data(objectMapper.writeValueAsString(wrapped)));
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

            return emitter;

        } else {
            // 非流式
            List<Map<String, Object>> collected = new ArrayList<>();
            executor.execute(
                    finalTaskId, conversation_id, finalCurrentTask,
                    userQuery, originalHeaders, body, queryParams,
                    collected::add
            );

            String answer = "";
            for (Map<String, Object> event : collected) {
                if ("agent".equals(event.get("_event_kind"))
                        && ("final_answer_chunk".equals(event.get("event"))
                        || "final_answer_end".equals(event.get("event")))) {
                    Object content = event.get("content");
                    if (content != null) {
                        answer = content.toString();
                    }
                }
            }

            return ResponseEntity.ok(Map.of("success", true, "answer", answer));
        }
    }

    private Map<String, Object> extractQueryParams(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            if (values == null) {
                continue;
            }
            result.put(entry.getKey(), values.length == 1 ? values[0] : Arrays.asList(values));
        }
        return result;
    }

    private String ensureTaskMapping(String conversationId) {
        String convTaskKey = String.format(CONV_TASK_KEY_TEMPLATE, conversationId);
        Optional<String> taskId = redis.get(convTaskKey);
        if (taskId.isPresent()) {
            return taskId.get();
        }
        String newTaskId = UUID.randomUUID().toString();
        boolean wasSet = redis.setNx(convTaskKey, newTaskId, REQUEST_TTL);
        return wasSet ? newTaskId : redis.get(convTaskKey).orElse(newTaskId);
    }

    private boolean checkRateLimit(String agentId, String conversationId) {
        try {
            int sessionMax = positive(settings.getRateLimitMaxRequests(), 1);
            int sessionWindow = positive(settings.getRateLimitWindowSeconds(), 10);
            int globalMax = positive(settings.getGlobalRateLimitMaxRequests(), 10);
            int globalWindow = positive(settings.getGlobalRateLimitWindowSeconds(), 10);
            double now = System.currentTimeMillis() / 1000.0d;

            String sessionKey = "a2a_service:rate_limit:" + agentId + ":session:" + conversationId;
            String globalKey = "a2a_service:rate_limit:" + agentId + ":global";

            redis.zRemoveRangeByScore(sessionKey, Double.NEGATIVE_INFINITY, now - sessionWindow);
            long sessionRequests = redis.zCard(sessionKey);
            if (sessionRequests >= sessionMax) {
                logger.warn("[RateLimit] 会话 {} 触发 Session 级限流", conversationId);
                return false;
            }

            redis.zRemoveRangeByScore(globalKey, Double.NEGATIVE_INFINITY, now - globalWindow);
            long globalRequests = redis.zCard(globalKey);
            boolean sessionExistsInGlobal = false;
            if (globalRequests >= globalMax) {
                String prefix = conversationId + ":";
                for (String member : redis.zRange(globalKey, 0, -1)) {
                    if (member != null && member.startsWith(prefix)) {
                        sessionExistsInGlobal = true;
                        break;
                    }
                }
            }
            if (globalRequests >= globalMax && !sessionExistsInGlobal) {
                logger.warn("[RateLimit] Agent {} 触发全局限流，新会话拦截，conversation_id={}", agentId, conversationId);
                return false;
            }

            String requestId = UUID.randomUUID().toString();
            redis.zAdd(sessionKey, requestId, now);
            redis.expire(sessionKey, sessionWindow * 2L);
            redis.zAdd(globalKey, conversationId + ":" + requestId, now);
            redis.expire(globalKey, globalWindow * 2L);
            return true;
        } catch (Exception e) {
            logger.warn("[RateLimit] 限流检查失败，fail-open: {}", e.getMessage());
            return true;
        }
    }

    private int positive(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wrapEvent(
            Map<String, Object> event,
            String agentId,
            String conversationId,
            long startedAt
    ) {
        double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0d;
        Object kind = event.get("_event_kind");
        if ("workflow".equals(kind)) {
            Object data = event.get("data");
            return ResponseWrapper.wrapWorkflowEvent(
                    String.valueOf(event.getOrDefault("event", "message")),
                    data instanceof Map<?, ?> map ? castMap(map) : Map.of(),
                    agentId,
                    conversationId,
                    elapsed
            );
        }
        if ("agent".equals(kind)) {
            Object data = event.get("data");
            return ResponseWrapper.wrapAgentEvent(
                    String.valueOf(event.getOrDefault("event", "")),
                    String.valueOf(event.getOrDefault("content", "")),
                    data instanceof Map<?, ?> map ? castMap(map) : Map.of(),
                    agentId,
                    conversationId,
                    elapsed,
                    String.valueOf(event.getOrDefault("plugin", ""))
            );
        }
        return ResponseWrapper.wrapAgentEvent(
                String.valueOf(event.getOrDefault("type", "status")),
                String.valueOf(event.getOrDefault("content", "")),
                event,
                agentId,
                conversationId,
                elapsed,
                ""
        );
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
