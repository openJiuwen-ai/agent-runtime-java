package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VersatileInterruptRail：拦截 query_balance 和 transfer 工具调用，通过 session state 传递委托信息。
 *
 * 设计原则：
 *   - 零 A2A 依赖：不引用 EventQueue、A2AClient 等任何 A2A 对象
 *   - 不主动调用外部 Agent：只记录委托意图，由 Orchestrator 执行
 *   - Cascade 续轮：从 session state 读取 cascade_result，直接 reject(toolResult=result)
 *
 * 数据流：
 *   Rail.resolveInterrupt()
 *     → session.updateState({"pending_delegate": {...}})   # 首次委托
 *     → interrupt()                                          # Runner 保存 Checkpoint
 *   agentStream() 在 runStream/resume 结束后
 *     → session.getState("pending_delegate")
 *     → yield DelegateRequest(...)                           # 传递给 Orchestrator
 */
public class VersatileInterruptRail extends BaseInterruptRail {

    private static final Logger logger = LoggerFactory.getLogger(VersatileInterruptRail.class);

    public VersatileInterruptRail() {
        super(List.of("query_balance", "transfer"));
        logger.info("[VersatileInterruptRail] 初始化完成，拦截工具：query_balance, transfer");
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        logger.info("[VersatileInterruptRail] 调用, toolCall={}", toolCall);
        // ── 提取工具参数 ──────────────────────────────────────────────────────
        Object rawToolArgs = null;
        if (ctx.getInputs() instanceof ToolCallInputs) {
            rawToolArgs = ((ToolCallInputs) ctx.getInputs()).getToolArgs();
        }
        String toolName = toolCall != null ? toolCall.getName() : null;

        // 兼容处理：toolArgs 可能是 String（JSON）或 Map
        Map<String, Object> toolArgs = new LinkedHashMap<>();
        if (rawToolArgs instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapArgs = (Map<String, Object>) rawToolArgs;
            toolArgs.putAll(mapArgs);
        } else if (rawToolArgs instanceof String) {
            // 尝试 JSON 解析
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue((String) rawToolArgs, Map.class);
                toolArgs.putAll(parsed);
            } catch (Exception e) {
                toolArgs.put("task_description", rawToolArgs);
            }
        }

        // ── Cascade 续轮快捷路径 ──────────────────────────────────────────────
        // Orchestrator 通过 agentStream(cascadeResult=...) 注入结果，
        // Agent.java 在续轮时调用 session.updateState({"cascade_result": ...})
        Object cascadeResult = ctx.getSession().getState("cascade_result");
        if (cascadeResult != null) {
            Map<String, Object> clearState = new HashMap<>();
            clearState.put("cascade_result", null);  // 消费
            ctx.getSession().updateState(clearState);
            logger.info("[VersatileInterruptRail] Cascade 续轮：注入 workflow_result 给 LLM，cascade_result={}", cascadeResult);
            return reject(cascadeResult);
        }

        // ── 首次拦截：记录委托意图 ────────────────────────────────────────────
        String taskDescription = toolArgs.getOrDefault("task_description", "").toString();

        // 根据工具名设置 intent
        String intent = "";
        if ("query_balance".equals(toolName)) {
            intent = "查询账户余额";
        } else if ("transfer".equals(toolName)) {
            intent = "快速转账";
        }

        String truncatedDesc = taskDescription.length() > 60 ? taskDescription.substring(0, 60) : taskDescription;
        logger.info("[VersatileInterruptRail] 拦截工具调用：tool={}, intent={}, desc='{}'", toolName, intent, truncatedDesc);

        ctx.getSession().updateState(Map.of(
                "pending_delegate", Map.of(
                        "intent", intent,
                        "task_description", taskDescription
                )
        ));

        logger.info("[VersatileInterruptRail] 委托意图已记录：intent={}, desc='{}'", intent, truncatedDesc);

        // interrupt() → Runner 保存 Checkpoint → agent.stream() 生成器结束
        // agentStream() 函数末尾会读取 pending_delegate 并 yield DelegateRequest
        return interrupt(InterruptRequest.builder()
                .message("执行" + intent + "，等待 Orchestrator Cascade 续轮")
                .build());
    }
}
