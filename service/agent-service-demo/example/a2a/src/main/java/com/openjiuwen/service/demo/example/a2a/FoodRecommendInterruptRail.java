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
import java.util.List;
import java.util.Map;

/**
 * Agent C DeepAgent food recommendation tool rail.
 *
 * @since 0.1.0
 */
public class FoodRecommendInterruptRail extends BaseInterruptRail {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final String TOOL_NAME = "food_recommend";

    private static final String DEFAULT_REQUEST = "Recommend a dish for a team meal";

    public FoodRecommendInterruptRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
                .description("Agent C food recommendation tool that asks the user for confirmation")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("request",
                                Map.of("type", "string", "description", "The dining or food recommendation request")),
                        "required", List.of("request")))
                .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            return reject("Agent C received confirmation: " + resumeInput + "; food recommendation: "
                    + recommendation(extractRequest(toolCall)));
        }
        var request = InterruptRequest.builder().message(
                "Agent C is ready to recommend food for: " + extractRequest(toolCall) + ". Confirm to continue.")
                .context(Map.of("_interrupt_kind", "ask_user")).build();
        return interrupt(request);
    }

    private static String recommendation(String request) {
        return "For '" + request + "', Agent C recommends Kung Pao chicken because it is easy to share.";
    }

    private static String extractRequest(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object request = args.get("request");
            if (request instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // arguments parse failed; fall through to deterministic demo default
        }
        return DEFAULT_REQUEST;
    }
}
