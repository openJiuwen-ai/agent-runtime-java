package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;
import com.openjiuwen.a2a_service.agents.EDPAgent.tool.MCPRecommendationSupport;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MCPInterruptRail extends BaseInterruptRail {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public MCPInterruptRail() {
        super(List.of("call_mcp"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        Map<String, Object> toolArgs = normalizeToolArgs(ctx);
        Map<String, Object> skillInput = MCPRecommendationSupport.buildSkillInput(toolArgs);
        Map<String, Object> mcpResult;
        try {
            mcpResult = MCPRecommendationSupport.runMcpRecommend(skillInput);
        } catch (Exception ignored) {
            mcpResult = MCPRecommendationSupport.errorResult(skillInput);
        }
        ctx.getSession().updateState(Map.of(StateKeys.MCP_PRODUCTS_DATA, mcpResult));
        return reject(mcpResult);
    }

    private Map<String, Object> normalizeToolArgs(AgentCallbackContext ctx) {
        Object toolArgs = ctx.getInputs() instanceof com.openjiuwen.core.singleagent.rail.ToolCallInputs inputs
                ? inputs.getToolArgs()
                : null;
        if (toolArgs instanceof String stringArgs) {
            try {
                return MAPPER.readValue(stringArgs, new TypeReference<Map<String, Object>>() { });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        if (toolArgs instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }
}
