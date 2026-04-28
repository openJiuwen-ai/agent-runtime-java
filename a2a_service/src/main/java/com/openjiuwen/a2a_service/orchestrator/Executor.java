package com.openjiuwen.a2a_service.orchestrator;

import com.openjiuwen.a2a_service.agents.EDPAgent.Agent;
import com.openjiuwen.a2a_service.common.Constants;
import com.openjiuwen.a2a_service.common.Events;
import com.openjiuwen.a2a_service.common.RedisClient;
import com.openjiuwen.a2a_service.common.RedisTaskStore;
import com.openjiuwen.a2a_service.config.Settings;
import io.a2a.client.Client;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executor — 核心编排逻辑。
 *
 * 职责：
 *   1. 处理首轮和续轮请求
 *   2. 首轮：调用 agentStream()，处理 DelegateRequest / AnswerEvent
 *   3. DelegateRequest：调用 VersatileAdapter，根据返回决定续轮或挂起
 *   4. 续轮：从 taskStore 读取 Task 状态，通过 metadata 传递 vaTaskId
 *
 * Task 状态流转（存于 RedisTaskStore）：
 *   WORKING → [DelegateRequest + VA 无 end node] → INPUT_REQUIRED（metadata.vaTaskId 已写入）
 *   INPUT_REQUIRED → [下一轮用户输入 + VA 有 end node] → WORKING → cascade → COMPLETED
 */
public class Executor {

    private static final Logger logger = LoggerFactory.getLogger(Executor.class);
    private static final int TTL = 1800;
    /** sendMessage 流式回调的超时时间（秒） */
    private static final int STREAM_TIMEOUT_SECONDS = 30;

    private final Client vaClient;
    private final RedisClient redis;
    private final RedisTaskStore taskStore;
    private final Settings settings;

    public Executor(Client vaClient, RedisClient redis, RedisTaskStore taskStore, Settings settings) {
        this.vaClient = vaClient;
        this.redis = redis;
        this.taskStore = taskStore;
        this.settings = settings;
    }

    public RedisTaskStore getTaskStore() {
        return taskStore;
    }

    /**
     * 执行入口。
     *
     * @param taskId         任务 ID
     * @param convId         会话 ID
     * @param currentTask    当前 task 信息（续轮时非 null）
     * @param userQuery      用户输入
     * @param headers        原始请求头
     * @param originalBody   原始请求体
     * @param eventSink      事件接收回调
     */
    public void execute(String taskId, String convId, RedisTaskStore.TaskInfo currentTask,
                        String userQuery, Map<String, Object> headers,
                        Map<String, Object> originalBody,
                        Map<String, Object> params,
                        java.util.function.Consumer<Map<String, Object>> eventSink) {

        // ── 续轮路径：Task 处于 INPUT_REQUIRED（VA 上次未完成）───────────────
        if (currentTask != null && currentTask.getState() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String vaTaskId = (String) currentTask.getMetadata().getOrDefault("va_task_id", "");
            logger.info("[Executor] INPUT_REQUIRED 续轮：conv={}, vaTask={}", convId, vaTaskId);
            continueVersatileAdapter(convId, taskId, vaTaskId, userQuery, headers, originalBody, params, eventSink);
            return;
        }

        // ── 首轮路径 ──────────────────────────────────────────────────────
        if (currentTask == null) {
            taskStore.save(new RedisTaskStore.TaskInfo(taskId, convId, TaskState.TASK_STATE_WORKING, new HashMap<>()));
            logger.info("[Executor] 创建 Task：task={}, conv={}", taskId, convId);
        }

        runAgent(convId, taskId, userQuery, originalBody, eventSink, null, new int[]{0});
    }

    /**
     * 核心递归编排。
     */
    private void runAgent(String convId, String taskId, String query,
                          Map<String, Object> originalBody,
                          java.util.function.Consumer<Map<String, Object>> eventSink,
                          Map<String, Object> cascadeResult,
                          int[] stepCounter) {

        List<Object> events = Agent.agentStream(query, convId, cascadeResult, Map.of("body", originalBody));

        for (Object event : events) {
            Map<String, Object> a2aEvent = AgentAdapter.agentEventToA2a(event, taskId, convId);
            if (a2aEvent != null && "tool_start".equals(a2aEvent.get("event"))) {
                stepCounter[0]++;
                String content = "[执行轨迹] 正在执行步骤" + stepCounter[0] + ": "
                        + String.valueOf(a2aEvent.getOrDefault("content", ""))
                        + " (tool=" + String.valueOf(a2aEvent.getOrDefault("plugin", "")) + ")";
                eventSink.accept(AgentAdapter.agentEvent("planning_execution_process", content, Map.of(), ""));
            }
            if (event instanceof Events.DelegateRequest delegate) {
                stepCounter[0]++;
                String content = "[执行轨迹] 正在执行步骤" + stepCounter[0] + ": "
                        + delegate.getTaskDescription() + " (tool=adapter:versatile_proxy)";
                eventSink.accept(AgentAdapter.agentEvent("planning_execution_process", content, Map.of(), ""));
                logger.info("[Executor] DelegateRequest → {}: {}", delegate.getIntent(), delegate.getTaskDescription());
                VaResult vaResult = callVersatileAdapter(delegate, convId, taskId, eventSink);

                if (vaResult.cascade != null) {
                    runAgent(convId, taskId, query, originalBody, eventSink, vaResult.cascade, stepCounter);
                } else {
                    // VA 未完成：将 vaTaskId 写入 Task metadata，状态改为 INPUT_REQUIRED
                    Optional<RedisTaskStore.TaskInfo> taskOpt = taskStore.get(taskId);
                    if (taskOpt.isPresent()) {
                        RedisTaskStore.TaskInfo task = taskOpt.get()
                                .withMetadata("va_task_id", vaResult.vaTaskId != null ? vaResult.vaTaskId : "")
                                .withState(TaskState.TASK_STATE_INPUT_REQUIRED);
                        taskStore.save(task);
                    }
                    eventSink.accept(Map.of(
                            "type", "status_update",
                            "taskId", taskId,
                            "contextId", convId,
                            "state", "INPUT_REQUIRED"
                    ));
                    logger.info("[Executor] VA 挂起：conv={}, vaTask={}", convId, vaResult.vaTaskId);
                }
                return;
            }

            if (a2aEvent != null) {
                eventSink.accept(a2aEvent);
            }
        }

        // agent stream 正常结束 → 写 COMPLETED 到 TaskStore
        Optional<RedisTaskStore.TaskInfo> taskOpt = taskStore.get(taskId);
        if (taskOpt.isPresent() && taskOpt.get().getState() != TaskState.TASK_STATE_COMPLETED) {
            taskStore.save(taskOpt.get().withState(TaskState.TASK_STATE_COMPLETED));
            logger.info("[Executor] Task 标记 COMPLETED：task={}, conv={}", taskId, convId);
        }
    }

    // ── VersatileAdapter 调用 ─────────────────────────────────────────────────

    /**
     * VA 调用结果。
     */
    private static class VaResult {
        final Map<String, Object> cascade;  // 非 null 表示 VA 已完成（有 end node）
        final String vaTaskId;               // VA 端真实 task ID

        VaResult(Map<String, Object> cascade, String vaTaskId) {
            this.cascade = cascade;
            this.vaTaskId = vaTaskId;
        }
    }

    /**
     * 临时兼容旧链路：推荐首跳改写为平台历史上可识别的入口。
     */
    private String[] rewriteRecommendDelegate(String intent, String taskDescription) {
        if (!"理财推荐".equals(intent)) {
            return new String[]{intent, taskDescription};
        }

        String normalizedQuery = taskDescription != null ? taskDescription.trim() : "";
        if (normalizedQuery.isBlank() || "推荐理财产品".equals(normalizedQuery)) {
            normalizedQuery = "请推荐低风险理财产品";
        }
        return new String[]{"理财选品购买", normalizedQuery};
    }

    /**
     * 构建 A2A Message 发送给 VA。
     */
    private Message buildVaMessage(String query, Map<String, Object> headers,
                                   Map<String, Object> body, String taskId, String convId) {
        return buildVaMessage(query, headers, body, Map.of(), taskId, convId);
    }

    private Message buildVaMessage(String query, Map<String, Object> headers,
                                   Map<String, Object> body, Map<String, Object> params,
                                   String taskId, String convId) {
        List<Part<?>> parts = new ArrayList<>();
        parts.add(new TextPart(query));
        parts.add(new DataPart(Map.of("headers", headers, "body", body, "params", params != null ? params : Map.of())));

        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .taskId(taskId)
                .contextId(convId)
                .parts(parts)
                .build();
    }

    /**
     * 从 TaskArtifactUpdateEvent 的 parts 中提取 DataPart 列表。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataParts(TaskArtifactUpdateEvent event) {
        List<Map<String, Object>> dataParts = new ArrayList<>();
        for (Part<?> part : event.artifact().parts()) {
            if (part instanceof DataPart dataPart) {
                Object data = dataPart.data();
                if (data instanceof Map) {
                    dataParts.add((Map<String, Object>) data);
                }
            }
        }
        return dataParts;
    }

    /**
     * 判断 artifact 是否包含 end node（node_type == "End"）。
     */
    private boolean hasEndNode(TaskArtifactUpdateEvent event) {
        for (Map<String, Object> data : extractDataParts(event)) {
            Map<String, Object> node = extractWorkflowNode(data);
            if ("End".equals(String.valueOf(node.get("node_type")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断该 artifact 是否为配置中需要屏蔽的节点（不推送给用户）。
     */
    private boolean isSuppressedNode(TaskArtifactUpdateEvent event) {
        String target = settings.getVaWorkflowResultNode();
        if (target == null || target.isEmpty()) {
            return false;
        }
        for (Map<String, Object> data : extractDataParts(event)) {
            Map<String, Object> node = extractWorkflowNode(data);
            if (target.equals(node.get("node_name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 artifact 中提取 QA 结果（匹配 node_type == "QA" 且 node_name == 配置值）。
     */
    private String extractQaNode(TaskArtifactUpdateEvent event) {
        String targetNode = settings.getVaWorkflowResultNode();
        for (Map<String, Object> data : extractDataParts(event)) {
            Map<String, Object> node = extractWorkflowNode(data);
            if (!"QA".equals(String.valueOf(node.get("node_type")))) {
                continue;
            }
            String nodeName = stringValue(node, "node_name");
            if (targetNode != null && !targetNode.isEmpty() && !targetNode.equals(nodeName)) {
                continue;
            }
            String text = firstNonBlank(
                    stringValue(node, "text"),
                    stringValue(node, "summary"),
                    stringValue(node, "content")
            );
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 调用 VersatileAdapter（首轮 — DPA 委托场景）。
     *
     * 从 Redis 取首轮缓存，替换 query/intent 后发给 VA，
     * 通过 A2A Client 的流式回调收集结果。
     */
    @SuppressWarnings("unchecked")
    private VaResult callVersatileAdapter(Events.DelegateRequest delegate, String convId,
                                          String taskId,
                                          java.util.function.Consumer<Map<String, Object>> eventSink) {
        // VA Client 不可用时直接返回挂起
        if (vaClient == null) {
            logger.warn("[Executor] VA Client 未初始化，跳过调用：conv={}", convId);
            return new VaResult(null, UUID.randomUUID().toString());
        }

        // 从 Redis 取首轮缓存
        Optional<Map<String, Object>> cachedOpt = redis.getJsonAsMap(Constants.sessionRequestKey(convId));
        Map<String, Object> cached = cachedOpt.orElse(new HashMap<>());
        Map<String, Object> headers = (Map<String, Object>) cached.getOrDefault("headers", new HashMap<>());
        Map<String, Object> body = new HashMap<>((Map<String, Object>) cached.getOrDefault("body", new HashMap<>()));
        Map<String, Object> params = new HashMap<>((Map<String, Object>) cached.getOrDefault("params", new HashMap<>()));

        String[] rewritten = rewriteRecommendDelegate(delegate.getIntent(), delegate.getTaskDescription());
        String effectiveIntent = rewritten[0];
        String effectiveQuery = rewritten[1];
        if (!Objects.equals(effectiveIntent, delegate.getIntent())
                || !Objects.equals(effectiveQuery, delegate.getTaskDescription())) {
            logger.info("[Executor] 推荐入口临时改写：intent={} -> {}, query={} -> {}",
                    delegate.getIntent(), effectiveIntent, delegate.getTaskDescription(), effectiveQuery);
        }

        // 修改 custom_data.inputs 和 input
        if (body.get("custom_data") instanceof Map customData) {
            Map<String, Object> cd = new HashMap<>(customData);
            if (cd.get("inputs") instanceof Map inputs) {
                Map<String, Object> newInputs = new HashMap<>((Map<String, Object>) inputs);
                newInputs.put("query", effectiveQuery);
                newInputs.put("intent", effectiveIntent);
                cd.put("inputs", newInputs);
            }
            body.put("custom_data", cd);
        }
        Map<String, Object> inputSection = new HashMap<>((Map<String, Object>) body.getOrDefault("input", new HashMap<>()));
        inputSection.put("query", effectiveQuery);
        inputSection.put("intent", effectiveIntent);
        body.put("input", inputSection);
        body.put("stream", true);

        // 构建消息并发送
        Message message = buildVaMessage(effectiveQuery, headers, body, params, "", convId);
        return streamCallVa(message, convId, eventSink);
    }

    /**
     * VA 挂起后，下一轮用户输入续轮。
     */
    @SuppressWarnings("unchecked")
    private void continueVersatileAdapter(String convId, String taskId, String vaTaskId,
                                          String userInput, Map<String, Object> headers,
                                          Map<String, Object> originalBody,
                                          Map<String, Object> params,
                                          java.util.function.Consumer<Map<String, Object>> eventSink) {
        // VA Client 不可用时直接挂起
        if (vaClient == null) {
            logger.warn("[Executor] VA Client 未初始化，续轮跳过：conv={}", convId);
            Optional<RedisTaskStore.TaskInfo> taskOpt = taskStore.get(taskId);
            if (taskOpt.isPresent()) {
                taskStore.save(taskOpt.get()
                        .withMetadata("va_task_id", vaTaskId != null ? vaTaskId : "")
                        .withState(TaskState.TASK_STATE_INPUT_REQUIRED));
            }
            eventSink.accept(Map.of(
                    "type", "status_update",
                    "taskId", taskId,
                    "contextId", convId,
                    "state", "INPUT_REQUIRED"
            ));
            return;
        }

        Map<String, Object> body = new HashMap<>(originalBody);
        body.put("stream", true);

        Message message = buildVaMessage(userInput, headers, body, params, vaTaskId, convId);
        VaResult vaResult = streamCallVa(message, convId, eventSink);

        if (vaResult.cascade != null) {
            logger.info("[Executor] VA 续轮 end node: conv={}", convId);

            Optional<RedisTaskStore.TaskInfo> taskOpt = taskStore.get(taskId);
            if (taskOpt.isPresent()) {
                taskStore.save(taskOpt.get().withState(TaskState.TASK_STATE_WORKING));
            }

            runAgent(convId, taskId, "", originalBody, eventSink, vaResult.cascade, new int[]{0});
        } else {
            // VA 仍未完成，继续挂起；va_task_id 不变
            Optional<RedisTaskStore.TaskInfo> taskOpt = taskStore.get(taskId);
            if (taskOpt.isPresent()) {
                taskStore.save(taskOpt.get()
                        .withMetadata("va_task_id", vaTaskId != null ? vaTaskId : "")
                        .withState(TaskState.TASK_STATE_INPUT_REQUIRED));
            }
            eventSink.accept(Map.of(
                    "type", "status_update",
                    "taskId", taskId,
                    "contextId", convId,
                    "state", "INPUT_REQUIRED"
            ));
            logger.info("[Executor] VA 续轮仍无 end node: conv={}, vaTask={}", convId, vaTaskId);
        }
    }

    /**
     * 通过 A2A Client 流式调用 VA，收集 end node / QA 结果。
     *
     * Java A2A SDK 的 sendMessage 是基于回调的（BiConsumer），
     * 使用 CountDownLatch 同步等待流式回调完成。
     */
    private VaResult streamCallVa(Message message, String convId,
                                  java.util.function.Consumer<Map<String, Object>> eventSink) {
        AtomicReference<String> vaRealTaskId = new AtomicReference<>(null);
        AtomicBoolean hasEndNode = new AtomicBoolean(false);
        AtomicBoolean requiresUserInput = new AtomicBoolean(false);
        AtomicBoolean terminalObserved = new AtomicBoolean(false);
        AtomicReference<String> qaResult = new AtomicReference<>(null);
        AtomicReference<Map<String, Object>> terminalWorkflowData = new AtomicReference<>(Map.of());
        AtomicReference<String> terminalWorkflowText = new AtomicReference<>("");
        AtomicReference<String> continuationTaskId = new AtomicReference<>(UUID.randomUUID().toString());
        CountDownLatch latch = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();

        try {
            vaClient.sendMessage(message,
                    List.of((event, card) -> {
                        try {
                            // TaskUpdateEvent 包含 TaskArtifactUpdateEvent / TaskStatusUpdateEvent
                            if (event instanceof TaskUpdateEvent tue) {
                                boolean isFinal = handleStreamUpdateEvent(tue, convId, eventSink,
                                        vaRealTaskId, hasEndNode, qaResult, continuationTaskId,
                                        requiresUserInput, terminalWorkflowData, terminalWorkflowText);
                                if (isFinal) {
                                    terminalObserved.set(true);
                                    latch.countDown();
                                }
                            } else if (event instanceof io.a2a.client.TaskEvent te) {
                                // Task 级别事件，提取 task id
                                Task task = te.getTask();
                                if (task != null && task.id() != null && !task.id().isEmpty()) {
                                    vaRealTaskId.set(task.id());
                                    continuationTaskId.set(task.id());
                                    logger.info("[Executor] VA real task_id={}, conv={}", task.id(), convId);
                                }
                                // 检查 Task 是否到达终态
                                if (task != null && task.status() != null && task.status().state().isFinal()) {
                                    terminalObserved.set(true);
                                    latch.countDown();
                                }
                            }
                        } catch (Exception e) {
                            logger.error("[Executor] 处理流式事件异常: {}", e.getMessage());
                        }
                    }),
                    error -> {
                        String messageText = error != null ? error.getMessage() : "";
                        if (terminalObserved.get()
                                && messageText != null
                                && messageText.toLowerCase(Locale.ROOT).contains("request cancelled")) {
                            logger.debug("[Executor] VA send_message 收尾取消，忽略: {}", messageText);
                            latch.countDown();
                            return;
                        }
                        logger.error("[Executor] VA send_message 异常: {}", messageText);
                        errors.add(error instanceof Exception ? (Exception) error : new RuntimeException(error));
                        latch.countDown();
                    },
                    null  // ClientCallContext
            );
        } catch (Exception e) {
            logger.error("[Executor] VA send_message 调用失败: {}", e.getMessage());
            return new VaResult(null, continuationTaskId.get());
        }

        // 等待流式回调完成
        try {
            boolean completed = latch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("[Executor] VA 流式回调超时：conv={}", convId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[Executor] VA 流式等待被中断：conv={}", convId);
        }

        if (!errors.isEmpty()) {
            logger.error("[Executor] VA 调用存在异常，conv={}", convId);
        }

        String taskId = continuationTaskId.get();

        if (hasEndNode.get()) {
            Map<String, Object> cascade = new HashMap<>();
            cascade.put("workflow_result",
                    firstNonBlank(qaResult.get(), terminalWorkflowText.get()).isBlank()
                            ? terminalWorkflowData.get()
                            : firstNonBlank(qaResult.get(), terminalWorkflowText.get()));
            logger.info("[Executor] VA end node: conv={}, qaResult={}", convId, qaResult.get());
            return new VaResult(cascade, taskId);
        }

        if (requiresUserInput.get()) {
            logger.info("[Executor] VA requires user input: conv={}, vaTask={}", convId, taskId);
            return new VaResult(null, taskId);
        }

        if (!terminalWorkflowData.get().isEmpty() || !terminalWorkflowText.get().isBlank()) {
            Map<String, Object> cascade = new HashMap<>();
            cascade.put("workflow_result",
                    !terminalWorkflowData.get().isEmpty() ? terminalWorkflowData.get() : terminalWorkflowText.get());
            logger.info("[Executor] VA completed without end node: conv={}, vaTask={}", convId, taskId);
            return new VaResult(cascade, taskId);
        }

        logger.info("[Executor] VA 无有效结果: conv={}, vaTask={}", convId, taskId);
        return new VaResult(null, taskId);
    }

    /**
     * 处理流式更新事件，提取 task_id、end node、QA 结果，并转发非屏蔽事件。
     *
     * @return true 表示流式已完成（终态事件）
     */
    private boolean handleStreamUpdateEvent(TaskUpdateEvent tue, String convId,
                                             java.util.function.Consumer<Map<String, Object>> eventSink,
                                             AtomicReference<String> vaRealTaskId,
                                             AtomicBoolean hasEndNode,
                                             AtomicReference<String> qaResult,
                                             AtomicReference<String> continuationTaskId,
                                             AtomicBoolean requiresUserInput,
                                             AtomicReference<Map<String, Object>> terminalWorkflowData,
                                             AtomicReference<String> terminalWorkflowText) {
        UpdateEvent updateEvent = tue.getUpdateEvent();

        if (updateEvent instanceof TaskArtifactUpdateEvent artifactEvent) {
            // 提取 VA 真实 task id
            String taskId = artifactEvent.taskId();
            if (taskId != null && !taskId.isEmpty() && vaRealTaskId.get() == null) {
                vaRealTaskId.set(taskId);
                continuationTaskId.set(taskId);
                logger.info("[Executor] VA real task_id={}, conv={}", taskId, convId);
            }

            // 检查 end node
            if (hasEndNode(artifactEvent)) {
                hasEndNode.set(true);
            }

            // 提取 QA 结果
            String qa = extractQaNode(artifactEvent);
            if (qa != null) {
                qaResult.set(qa);
            }

            // 非屏蔽事件转发给用户
            if (!isSuppressedNode(artifactEvent)) {
                for (Map<String, Object> data : extractDataParts(artifactEvent)) {
                    String eventKind = stringValue(data, "event");
                    Map<String, Object> node = extractWorkflowNode(data);

                    String dialogId = stringValue(node, "dialog_id");
                    if (!dialogId.isBlank()) {
                        continuationTaskId.set(dialogId);
                    }
                    if ("QA".equals(String.valueOf(node.get("node_type")))) {
                        requiresUserInput.set(true);
                    }
                    if (isMeaningfulWorkflowPayload(eventKind, node)) {
                        terminalWorkflowData.set(new LinkedHashMap<>(node));
                        String candidateText = firstNonBlank(
                                stringValue(node, "text"),
                                stringValue(node, "summary"),
                                stringValue(node, "content")
                        );
                        if (!candidateText.isBlank()) {
                            terminalWorkflowText.set(candidateText);
                        }
                    }

                    if (data.containsKey("event") && data.get("data") instanceof Map<?, ?>) {
                        if (!"end".equals(eventKind)) {
                            eventSink.accept(AgentAdapter.workflowEvent(eventKind, node));
                        }
                    } else if (!node.isEmpty()) {
                        eventSink.accept(AgentAdapter.workflowEvent("message", node));
                    }
                }
            }

            // lastChunk=true 表示最后一个 artifact chunk，但还需要等终态 status
            return false;
        } else if (updateEvent instanceof TaskStatusUpdateEvent statusEvent) {
            // 提取 task id
            String taskId = statusEvent.taskId();
            if (taskId != null && !taskId.isEmpty() && vaRealTaskId.get() == null) {
                vaRealTaskId.set(taskId);
                continuationTaskId.set(taskId);
            }

            // 终态 → 通知 latch
            return statusEvent.isFinal();
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractWorkflowNode(Map<String, Object> frame) {
        if (frame == null) {
            return Map.of();
        }
        Object customRspData = frame.get("custom_rsp_data");
        if (customRspData instanceof Map<?, ?> wrapped) {
            Object wrappedData = wrapped.get("data");
            if (wrappedData instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return result;
            }
        }
        Object data = frame.get("data");
        if (frame.containsKey("event") && data instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return frame;
    }

    private boolean isMeaningfulWorkflowPayload(String eventKind, Map<String, Object> node) {
        if (node == null || node.isEmpty()) {
            return false;
        }
        if (!firstNonBlank(
                stringValue(node, "text"),
                stringValue(node, "summary"),
                stringValue(node, "content"),
                stringValue(node, "raw_content")
        ).isBlank()) {
            return true;
        }
        if ("rawData".equals(eventKind)) {
            return node.size() > 3;
        }
        return Set.of("answer", "message", "text").contains(eventKind);
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
