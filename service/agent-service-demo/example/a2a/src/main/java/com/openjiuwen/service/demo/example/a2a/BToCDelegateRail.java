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
 * Rail for Agent B: delegates selected work to Agent C through an A2A
 * interrupt. Agent C owns the user-confirmation interrupt in the three-agent
 * demo path.
 *
 * @since 0.1.0
 */
public class BToCDelegateRail extends BaseInterruptRail {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final String TOOL_NAME = "delegate_to_agentc";

    private static final String AGENT_NAME = "agentc";

    public BToCDelegateRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
                .description("Delegate dining requests to Agent C DeepAgent for final food recommendation")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("message",
                                Map.of("type", "string", "description",
                                        "The dining request to forward to Agent C's DeepAgent")),
                        "required", List.of("message")))
                .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            return reject(resumeInput);
        }
        String userQuery = extractMessage(toolCall);
        var request = InterruptRequest.builder().message(userQuery)
                .context(Map.of("agentName", AGENT_NAME, "_interrupt_kind", "a2a_delegate")).build();
        return interrupt(request);
    }

    private static String extractMessage(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object msg = args.get("message");
            if (msg instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // arguments parse failed; fall through to AGENT_NAME
        }
        return AGENT_NAME;
    }
}
