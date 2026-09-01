/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates structured expense claims from Agent B to Agent D's workflow.
 *
 * @since 0.1.0
 */
public class BToDDelegateRail extends BaseInterruptRail {
    static final String STREAMING_TOOL_NAME = "review_expense_streaming";

    static final String NON_STREAMING_TOOL_NAME = "review_expense_nonstreaming";

    private static final String STREAMING_AGENT_NAME = "agentd-streaming";

    private static final String NON_STREAMING_AGENT_NAME = "agentd-nonstreaming";

    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    public BToDDelegateRail() {
        super(List.of(STREAMING_TOOL_NAME, NON_STREAMING_TOOL_NAME));
        getTools().add(expenseReviewCard(STREAMING_TOOL_NAME,
                "Review an expense claim through Agent D's configured streaming route"));
        getTools().add(expenseReviewCard(NON_STREAMING_TOOL_NAME,
                "Review an expense claim through Agent D's configured non-streaming route"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            return reject(resumeInput);
        }
        String agentName = NON_STREAMING_TOOL_NAME.equals(toolCall.getName())
                ? NON_STREAMING_AGENT_NAME
                : STREAMING_AGENT_NAME;
        Map<String, Object> context = Map.of("agentName", agentName, "_interrupt_kind", "a2a_delegate");
        return interrupt(InterruptRequest.builder().message(canonicalClaim(toolCall)).context(context).build());
    }

    private static ToolCard expenseReviewCard(String toolName, String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("claim_id", Map.of("type", "string", "description", "Stable expense claim identifier"));
        properties.put("category", Map.of("type", "string", "enum", List.of("hotel", "meal", "transport", "other"),
                "description", "Expense category"));
        properties.put("unit_price", Map.of("type", "number", "description", "Price per night, meal, or item"));
        properties.put("quantity", Map.of("type", "number", "description", "Number of nights, meals, or items"));
        properties.put("total", Map.of("type", "number", "description", "Total claim amount"));
        properties.put("currency", Map.of("type", "string", "description", "Currency code, normally CNY"));
        return ToolCard.builder().id(toolName).name(toolName).description(description)
                .inputParams(Map.of("type", "object", "properties", properties, "required",
                        List.of("claim_id", "category", "unit_price", "quantity", "total", "currency")))
                .build();
    }

    private static String canonicalClaim(ToolCall toolCall) {
        Map<String, Object> source = Map.of();
        try {
            Map<String, Object> parsed = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            if (parsed != null) {
                source = parsed;
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // Use deterministic defaults so Agent D can return a useful validation result.
        }
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("claim_id", source.getOrDefault("claim_id", "UNKNOWN-CLAIM"));
        claim.put("category", source.getOrDefault("category", "other"));
        claim.put("unit_price", source.getOrDefault("unit_price", 0));
        claim.put("quantity", source.getOrDefault("quantity", 1));
        claim.put("total", source.getOrDefault("total", 0));
        claim.put("currency", source.getOrDefault("currency", "CNY"));
        return GSON.toJson(claim);
    }
}
