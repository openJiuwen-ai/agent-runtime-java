package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;
import com.openjiuwen.a2a_service.agents.EDPAgent.tool.VersatileResultNormalizer;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VersatileInterruptRail extends BaseInterruptRail {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String sysOperationId;

    public VersatileInterruptRail() {
        this(null);
    }

    public VersatileInterruptRail(String sysOperationId) {
        super(List.of("call_versatile"));
        this.sysOperationId = sysOperationId;
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        Map<String, Object> toolArgs = normalizeToolArgs(ctx);
        Object cascadeResult = ctx.getSession().getState(StateKeys.CASCADE_RESULT);
        if (cascadeResult != null) {
            Object normalizedResult = handleCascadeResume(ctx, toolArgs, cascadeResult);
            Map<String, Object> clearedState = new LinkedHashMap<String, Object>();
            clearedState.put(StateKeys.CASCADE_RESULT, null);
            clearedState.put(StateKeys.PENDING_TOOL_CONTEXT, null);
            clearedState.put(StateKeys.PENDING_DELEGATE, null);
            ctx.getSession().updateState(clearedState);
            return reject(normalizedResult);
        }

        Map<String, Object> mcpProductsData = normalizeMap(ctx.getSession().getState(StateKeys.MCP_PRODUCTS_DATA));
        Map<String, Object> pendingDelegate = new LinkedHashMap<String, Object>();
        pendingDelegate.put("intent", resolveDelegateIntent(toolArgs));
        pendingDelegate.put("target_agent", stringValue(toolArgs, "agent_name"));
        pendingDelegate.put("task_description", buildDelegateTaskDescription(toolArgs, mcpProductsData));
        Map<String, Object> pendingToolContext = new LinkedHashMap<String, Object>();
        pendingToolContext.put("tool_name", toolCall != null ? toolCall.getName() : "");
        pendingToolContext.put("tool_args", toolArgs);
        pendingToolContext.put("mcp_products_data", mcpProductsData);

        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put(StateKeys.PENDING_DELEGATE, pendingDelegate);
        update.put(StateKeys.PENDING_TOOL_CONTEXT, pendingToolContext);
        update.put(StateKeys.MCP_PRODUCTS_DATA, null);
        ctx.getSession().updateState(update);

        return interrupt(InterruptRequest.builder()
                .interruptId(toolCall != null ? toolCall.getId() : "")
                .message("执行" + resolveDelegateIntent(toolArgs) + "，等待 Orchestrator Cascade 续轮")
                .build());
    }

    private Object handleCascadeResume(
            AgentCallbackContext ctx,
            Map<String, Object> currentToolArgs,
            Object cascadeResult
    ) {
        Map<String, Object> toolContext = normalizeMap(ctx.getSession().getState(StateKeys.PENDING_TOOL_CONTEXT));
        Map<String, Object> toolArgs = normalizeMap(toolContext.get("tool_args"));
        if (toolArgs.isEmpty()) {
            toolArgs = currentToolArgs;
        }
        Map<String, Object> mcpProductsData = normalizeMap(toolContext.get("mcp_products_data"));

        Map<String, Object> businessData = extractBusinessData(cascadeResult);
        String command = stringValue(toolArgs, "query_response_analysis_scripts");
        if (command.isBlank()) {
            return businessData;
        }
        if (VersatileResultNormalizer.supports(command)) {
            return VersatileResultNormalizer.normalize(command, toolArgs, businessData, mcpProductsData);
        }

        Object shellNormalized = executeExternalNormalizer(command, toolArgs, businessData, mcpProductsData);
        return shellNormalized != null ? shellNormalized : businessData;
    }

    private Map<String, Object> normalizeToolArgs(AgentCallbackContext ctx) {
        Object toolArgs = ctx.getInputs() instanceof com.openjiuwen.core.singleagent.rail.ToolCallInputs inputs
                ? inputs.getToolArgs()
                : null;
        if (toolArgs instanceof String stringArgs) {
            try {
                return MAPPER.readValue(stringArgs, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return normalizeMap(toolArgs);
    }

    private Map<String, Object> extractBusinessData(Object cascadeResult) {
        Map<String, Object> result = normalizeMap(cascadeResult);
        Object workflowResult = result.get("workflow_result");
        if (workflowResult instanceof Map<?, ?> workflowMap) {
            return normalizeMap(workflowMap);
        }
        if (workflowResult instanceof String text) {
            try {
                return MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                return Map.of("workflow_result", text);
            }
        }
        result.remove("node_type");
        result.remove("node_name");
        return result;
    }

    private Object executeExternalNormalizer(
            String command,
            Map<String, Object> toolArgs,
            Map<String, Object> businessData,
            Map<String, Object> mcpProductsData
    ) {
        if (sysOperationId == null || sysOperationId.isBlank()) {
            return null;
        }
        Object sysOperationObj = Runner.resourceMgr().getSysOperation(
                sysOperationId,
                null,
                TagMatchStrategy.ALL
        );
        if (!(sysOperationObj instanceof SysOperation sysOperation)) {
            return null;
        }

        Map<String, Object> skillInput = new LinkedHashMap<String, Object>();
        skillInput.put("query_intent", stringValue(toolArgs, "query_intent"));
        skillInput.put("query_description", stringValue(toolArgs, "query_description"));
        skillInput.put("business_data", businessData);
        if (mcpProductsData != null && !mcpProductsData.isEmpty()) {
            skillInput.put("mcp_products_data", mcpProductsData);
        }
        mergeSkillContext(skillInput, stringValue(toolArgs, "skill_context"));
        if (!skillInput.containsKey("is_first_recommend")) {
            skillInput.put("is_first_recommend", isFirstRecommend(skillInput));
        }

        try {
            Object result = sysOperation.shell().executeCmd(
                    command,
                    "skills",
                    60,
                    Map.of("SKILL_INPUT", MAPPER.writeValueAsString(skillInput)),
                    Map.of()
            );
            if (result instanceof ExecuteCmdResult executeCmdResult
                    && executeCmdResult.getData() != null
                    && executeCmdResult.getData().getStdout() != null) {
                String stdout = executeCmdResult.getData().getStdout().trim();
                if (!stdout.isEmpty()) {
                    return MAPPER.readValue(stdout, new TypeReference<Map<String, Object>>() {
                    });
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String buildDelegateTaskDescription(Map<String, Object> toolArgs, Map<String, Object> mcpProductsData) {
        String taskDescription = stringValue(toolArgs, "query_description");
        Object productsObj = mcpProductsData != null ? mcpProductsData.get("products") : null;
        if (!(productsObj instanceof List<?> products) || products.isEmpty()) {
            return taskDescription;
        }
        try {
            String productsJson = MAPPER.writeValueAsString(products);
            Map<String, Object> updatedParams = normalizeMap(mcpProductsData.get("updated_recommend_params"));
            Map<String, String> filterData = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Object> entry : updatedParams.entrySet()) {
                if (entry.getValue() != null) {
                    filterData.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("productListJsonData", productsJson);
            data.put("filter_data", MAPPER.writeValueAsString(filterData));
            String mcpQuery = "理财二次选品购买：" + MAPPER.writeValueAsString(data);
            return taskDescription.isBlank() ? mcpQuery : taskDescription + "\n" + mcpQuery;
        } catch (Exception ignored) {
            return taskDescription;
        }
    }

    private String resolveDelegateIntent(Map<String, Object> toolArgs) {
        String intent = stringValue(toolArgs, "query_intent");
        if (!intent.isBlank()) {
            return intent;
        }
        String agentName = stringValue(toolArgs, "agent_name");
        if (!agentName.isBlank()) {
            return agentName;
        }
        String taskDescription = stringValue(toolArgs, "query_description");
        return !taskDescription.isBlank() ? taskDescription : "通用工作流";
    }

    private void mergeSkillContext(Map<String, Object> skillInput, String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return;
        }
        try {
            Map<String, Object> context = MAPPER.readValue(rawContext, new TypeReference<Map<String, Object>>() { });
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (!skillInput.containsKey(entry.getKey())) {
                    skillInput.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isFirstRecommend(Map<String, Object> skillInput) {
        return normalizeList(skillInput.get("history_product_codes")).isEmpty()
                && intValue(skillInput.get("current_sort_type")) == 0
                && normalizeMap(skillInput.get("history_recommend_params")).isEmpty();
    }

    private List<Object> normalizeList(Object value) {
        if (value instanceof List<?> list) {
            return new java.util.ArrayList<Object>(list);
        }
        return List.of();
    }

    private int intValue(Object value) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Map<String, Object> normalizeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
