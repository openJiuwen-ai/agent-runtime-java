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
    static final String STREAMING_TOOL_NAME = "delegate_to_agentc_streaming";

    static final String NON_STREAMING_TOOL_NAME = "delegate_to_agentc_nonstreaming";

    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final String STREAMING_AGENT_NAME = "agentc-streaming";

    private static final String NON_STREAMING_AGENT_NAME = "agentc-nonstreaming";

    public BToCDelegateRail() {
        super(List.of(STREAMING_TOOL_NAME, NON_STREAMING_TOOL_NAME));
        getTools().add(delegateCard(STREAMING_TOOL_NAME, "Delegate to Agent C using its streaming route"));
        getTools().add(delegateCard(NON_STREAMING_TOOL_NAME, "Delegate to Agent C using its non-streaming route"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            return reject(resumeInput);
        }
        String userQuery = extractMessage(toolCall);
        String agentName = NON_STREAMING_TOOL_NAME.equals(toolCall.getName())
                ? NON_STREAMING_AGENT_NAME
                : STREAMING_AGENT_NAME;
        var request = InterruptRequest.builder().message(userQuery)
                .context(Map.of("agentName", agentName, "_interrupt_kind", "a2a_delegate")).build();
        return interrupt(request);
    }

    private static ToolCard delegateCard(String toolName, String description) {
        return ToolCard.builder().id(toolName).name(toolName).description(description)
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("message",
                                Map.of("type", "string", "description",
                                        "The dining request to forward to Agent C's DeepAgent")),
                        "required", List.of("message")))
                .build();
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
        return STREAMING_AGENT_NAME;
    }
}
