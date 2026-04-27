package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleConfig;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionLimitRail extends AgentRail {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 5;

    private final Map<String, Integer> taskLimits;
    private final Map<String, String> scripts;

    public ExecutionLimitRail(AgentRuleConfig config) {
        this.taskLimits = new LinkedHashMap<String, Integer>(config.getLimits().getTasks());
        this.scripts = new LinkedHashMap<String, String>(config.getScripts());
        setPriority(40);
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }

        Map<String, Integer> execCounts = getExecCounts(ctx);
        String toolName = inputs.getToolName();
        int currentCount = execCounts.getOrDefault(toolName, 0);
        int limit = getToolLimit(toolName);

        if (currentCount >= limit) {
            ctx.requestForceFinish(Map.of(
                    "type", "execution_limit_exceeded",
                    "content", "工具 " + toolName + " 已达到执行次数限制(" + limit + ")",
                    "tool_name", toolName,
                    "count", currentCount
            ));
            return;
        }

        execCounts.put(toolName, currentCount + 1);
        ctx.getSession().updateState(Map.of(StateKeys.EXEC_COUNTS, execCounts));

        String template = scripts.getOrDefault("tool_start", "正在调用：{tool_name}");
        SessionStreamSupport.write(ctx.getSession(), new OutputSchema(
                "tool_start",
                0,
                Map.of(
                        "content", template.replace("{tool_name}", toolName),
                        "plugin", toolName,
                        "args", parseArgs(inputs.getToolArgs())
                )
        ));
    }

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }

        String toolName = inputs.getToolName();
        String template = scripts.getOrDefault("tool_end", "{tool_name} 执行完成");
        SessionStreamSupport.write(ctx.getSession(), new OutputSchema(
                "tool_end",
                0,
                Map.of(
                        "content", template.replace("{tool_name}", toolName),
                        "plugin", toolName,
                        "data", extractResultData(inputs.getToolResult())
                )
        ));
    }

    private int getToolLimit(String toolName) {
        Integer limit = taskLimits.get(toolName);
        if (limit != null) {
            return limit.intValue();
        }
        Integer defaultLimit = taskLimits.get("default");
        return defaultLimit != null ? defaultLimit.intValue() : DEFAULT_LIMIT;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> getExecCounts(AgentCallbackContext ctx) {
        Object state = ctx.getSession().getState(StateKeys.EXEC_COUNTS);
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (state instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number number) {
                    result.put(String.valueOf(entry.getKey()), number.intValue());
                }
            }
        }
        return result;
    }

    private Object parseArgs(Object toolArgs) {
        if (toolArgs instanceof String stringArgs) {
            try {
                return MAPPER.readValue(stringArgs, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return toolArgs != null ? toolArgs : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResultData(Object toolResult) {
        if (toolResult instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }
}
