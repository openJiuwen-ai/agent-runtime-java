package com.openjiuwen.a2a_service.agents.EDPAgent;

import com.openjiuwen.a2a_service.agents.EDPAgent.tool.QueryBalance;
import com.openjiuwen.a2a_service.agents.EDPAgent.tool.Transfer;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.VersatileInterruptRail;
import com.openjiuwen.a2a_service.common.Events;
import com.openjiuwen.a2a_service.config.DPASettings;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * DPA Agent 唯一公开入口。
 *
 * 公开接口：
 *   - initializeDpa()  — 应用启动时调用一次，配置 Runner 和 ReActAgent
 *   - agentStream()    — 每次用户请求时调用，流式返回 AgentEvent
 *
 * 零 A2A 依赖：本模块不引用任何 A2A SDK 类。
 */
public class Agent {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);

    // ── 模块级单例（DPA 初始化一次）─────────────────────────────────────────────
    private static volatile ReActAgent agent = null;

    /**
     * 应用启动时调用一次。
     *
     * 执行内容：
     *   1. 配置 Redis Checkpointer（断点续轮核心）
     *   2. 启动 Runner
     *   3. 创建 ReActAgent 并注册工具和 Rail
     */
    public static synchronized void initializeDpa(DPASettings settings) {
        if (agent != null) {
            logger.debug("[DPA] 已初始化，跳过重复初始化");
            return;
        }

        logger.info("settings is {}",settings);

        // ── 配置 Redis Checkpointer ──────────────────────────────────────────
        RunnerConfig runnerConfig = RunnerConfig.builder()
                .distributedMode(false)
                .checkpointerConfig(Map.of(
                        "type", "redis",
                        "conf", Map.of(
                                "connection", Map.of("url", settings.getRedisUrl()),
                                "ttl", Map.of(
                                        "default_ttl", settings.getRedisCheckpointerTtlMinutes(),
                                        "refresh_on_read", true
                                )
                        )
                ))
                .build();
        Runner.setConfig(runnerConfig);
        Runner.start();
        logger.info("[DPA] Runner 已启动，Checkpointer=redis");

        // ── 创建 ReActAgent ──────────────────────────────────────────────────
        AgentCard card = AgentCard.builder()
                .id(settings.getDpaAgentId())
                .name(settings.getDpaAgentName())
                .build();

        agent = new ReActAgent(card);

        Map<String, String> customHeaders = new HashMap<>();
        if (settings.getLlmUserId() != null && !settings.getLlmUserId().isEmpty()) {
            customHeaders.put("userId", settings.getLlmUserId());
        }
        if (settings.getLlmToken() != null && !settings.getLlmToken().isEmpty()) {
            customHeaders.put("token", settings.getLlmToken());
        }

        // 使用 ReActAgentConfig builder 模式配置 agent
        ReActAgentConfig config = ReActAgentConfig.builder()
                .modelName(settings.getLlmModelName())
                .modelProvider(settings.getLlmProvider())
                .apiKey(settings.getLlmApiKey())
                .apiBase(settings.getLlmApiBase())
                .promptTemplate(List.of(
                        Map.of("role", "system", "content", Prompt.buildSystemPrompt())
                ))
                .maxIterations(settings.getDpaMaxIterations())
                .build();

        // 配置自定义 headers（userId, token）
        config.configureModelClient(
                settings.getLlmProvider(),
                settings.getLlmApiKey(),
                settings.getLlmApiBase(),
                settings.getLlmModelName(),
                settings.isLlmVerifySsl(),
                null,
                customHeaders
        );

        logger.info("config is {}", config);

        agent.configure(config);

        // ── 注册工具 ─────────────────────────────────────────────────────────
        Runner.resourceMgr().addTool(QueryBalance.QUERY_BALANCE_TOOL, null);
        agent.getAbilityManager().add(QueryBalance.QUERY_BALANCE_TOOL.getCard());

        Runner.resourceMgr().addTool(Transfer.TRANSFER_TOOL, null);
        agent.getAbilityManager().add(Transfer.TRANSFER_TOOL.getCard());

        // ── 注册 Rail（零 A2A 依赖）──────────────────────────────────────────────
        agent.registerRail(new VersatileInterruptRail());

        logger.info("[DPA] 初始化完成：agentId={}", settings.getDpaAgentId());
    }

    /**
     * DPA 唯一入口。零 A2A 依赖。
     *
     * 首轮（cascadeResult=null）：
     *   - session.preRun → runner.runStream → yield events
     *   - 若 VersatileInterruptRail 触发 interrupt：session state 写入 pendingDelegate
     *   - stream 结束后检查 pending → yield DelegateRequest
     *
     * Cascade 续轮（cascadeResult 非 null）：
     *   - session 从 Checkpoint 恢复（preRun 自动加载）
     *   - 注入 cascadeResult → VersatileInterruptRail 读取 → reject(toolResult) → runner 继续
     *   - yield events（可能再次触发中断事件 → 多轮委托）
     *
     * @param query          用户输入文本
     * @param convId         会话 ID
     * @param cascadeResult  Cascade 续轮时注入的结果（null 表示首轮）
     * @param context        透传上下文，通常包含 {"body": originalBody}
     * @return 事件列表
     */
    public static List<Object> agentStream(String query, String convId, Map<String, Object> cascadeResult, Map<String, Object> context) {
        ReActAgent currentAgent = getAgent();
        @SuppressWarnings("unchecked")
        Map<String, Object> originalBody = (Map<String, Object>) (context != null ? context.getOrDefault("body", Map.of()) : Map.of());

        AgentSessionApi session = AgentSessionApi.create(convId, new HashMap<>(), currentAgent.getCard());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("query", cascadeResult != null ? "continue" : query);
        inputs.put("conversation_id", convId);

        if (cascadeResult != null) {
            // ── Cascade 续轮路径 ──────────────────────────────────────────
            logger.info("[DPA] Cascade 续轮：convId={}", convId);
            session.preRun(inputs);
            Map<String, Object> cascadeState = new HashMap<>();
            cascadeState.put("cascade_result", cascadeResult);
            cascadeState.put("original_body", originalBody);
            cascadeState.put("pending_delegate", null);  // 防止 preRun 恢复旧值导致循环触发
            session.updateState(cascadeState);
        } else {
            // ── 首轮路径 ──────────────────────────────────────────────────
            logger.info("[DPA] 首轮：convId={}, query={}", convId, query.length() > 60 ? query.substring(0, 60) : query);
            session.preRun(inputs);
            session.updateState(Map.of("original_body", originalBody));
        }

        List<Object> events = new ArrayList<>();
        try {
            Iterator<Object> streamIter = currentAgent.stream(inputs, session, null);
            while (streamIter.hasNext()) {
                Object rawEvent = streamIter.next();
                Object converted = convertEvent(rawEvent);
                if (converted != null) {
                    events.add(converted);
                }
            }
        } catch (Exception e) {
            logger.error("[DPA] agent stream 异常：{}", e.getMessage(), e);
        }

        // ── Rail 中断后的挂起事件检查 ─────────────────────────────────────────
        Object pendingDelegateObj = session.getState("pending_delegate");
        if (pendingDelegateObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pendingDelegate = (Map<String, Object>) pendingDelegateObj;
            logger.info("[DPA] 检测到委托请求：intent={}, desc={}",
                    pendingDelegate.get("intent"),
                    pendingDelegate.getOrDefault("task_description", ""));
            events.add(new Events.DelegateRequest(
                    (String) pendingDelegate.getOrDefault("intent", ""),
                    null,  // targetAgent
                    (String) pendingDelegate.getOrDefault("task_description", "")
            ));
            Map<String, Object> clearDelegate = new HashMap<>();
            clearDelegate.put("pending_delegate", null);
            session.updateState(clearDelegate);
        }

        return events;
    }

    /**
     * 获取已初始化的 Agent 实例。
     */
    private static ReActAgent getAgent() {
        if (agent == null) {
            throw new RuntimeException("DPA 未初始化，请先调用 initializeDpa()");
        }
        return agent;
    }

    /**
     * OpenJiuwen Runner 原始事件 → DPA 内部事件类型。
     *
     * Runner 实际 yield 的 OutputSchema 事件类型：
     *   "llm_reasoning" → ThoughtEvent（LLM 推理过程）
     *   "llm_output"    → AnswerEvent(final=false)（LLM 流式输出片段）
     *   "answer"        → AnswerEvent(final=true)（invoke 完成后的最终结果）
     */
    private static Object convertEvent(Object rawEvent) {
        if (rawEvent == null) {
            return null;
        }

        String eventType = null;
        Map<String, Object> payload = null;

        if (rawEvent instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawEvent;
            eventType = (String) map.get("type");
            payload = (Map<String, Object>) map.get("payload");
        } else {
            // 尝试通过反射获取 type 和 payload
            try {
                eventType = (String) rawEvent.getClass().getMethod("getType").invoke(rawEvent);
                Object payloadObj = rawEvent.getClass().getMethod("getPayload").invoke(rawEvent);
                if (payloadObj instanceof Map) {
                    payload = (Map<String, Object>) payloadObj;
                }
            } catch (Exception e) {
                return null;
            }
        }

        String content = "";
        if (payload != null) {
            Object output = payload.get("output");
            Object payloadContent = payload.get("content");
            content = output != null ? output.toString() : (payloadContent != null ? payloadContent.toString() : "");
        }

        if ("llm_reasoning".equals(eventType)) {
            return new Events.ThoughtEvent(content);
        }
        if ("llm_output".equals(eventType)) {
            return new Events.AnswerEvent(content, false);
        }
        if ("answer".equals(eventType)) {
            return new Events.AnswerEvent(content, true);
        }

        return null;
    }
}
