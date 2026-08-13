/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Skill-like echo tool for concurrent session validation.
 *
 * @since 0.1.0
 */
public class SkillEchoRail extends BaseInterruptRail {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    /** Registered tool name for the skill echo rail. */
    public static final String TOOL_NAME = "skill_echo";

    static final String RESULT_PREFIX = "ECHO:";

    public SkillEchoRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
            .description("Echo a session token for concurrency and skill validation")
            .inputParams(Map.of("type", "object", "properties",
                Map.of("token", Map.of("type", "string", "description", "Session or benchmark token to echo")),
                "required", List.of("token")))
            .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        String token = extractToken(toolCall);
        String sessionId = ctx.getSession() != null ? ctx.getSession().getSessionId() : "unknown";
        return reject(RESULT_PREFIX + token + ";session=" + sessionId);
    }

    static String extractToken(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object token = args.get("token");
            if (token instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // fall through
        }
        return "missing-token";
    }
}
