/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import com.google.gson.Gson;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.service.demo.example.concurrency.ConcurrentLookupRail;
import com.openjiuwen.service.demo.example.concurrency.SkillEchoRail;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic tool-calling planner for concurrency demo mock LLM responses.
 *
 * @since 0.1.0
 */
public final class ConcurrencyMockResponsePlanner {
    private static final Gson GSON = new Gson();

    private static final Pattern LOOKUP_REQUEST = Pattern.compile("^lookup:([^\\s]+)(?:\\s+delayMs=(\\d+))?$");

    private ConcurrencyMockResponsePlanner() {
    }

    /**
     * Builds the next assistant message for a model round trip.
     *
     * @param messages converted chat messages
     * @return assistant message with either tool calls or final text
     */
    public static AssistantMessage plan(List<Map<String, Object>> messages) {
        String latestToolResult = latestToolResultForCurrentTurn(messages);
        if (latestToolResult != null) {
            return AssistantMessage.builder().role("assistant")
            .content("Mock LLM summary: " + latestToolResult).build();
        }
        String userRequest = latestUserContent(messages);
        if (userRequest.startsWith("skill_echo:")) {
            String token = userRequest.substring("skill_echo:".length()).trim();
            return toolCall(SkillEchoRail.TOOL_NAME, Map.of("token", token));
        }
        if (userRequest.startsWith("lookup:")) {
            Matcher matcher = LOOKUP_REQUEST.matcher(userRequest.trim());
            if (matcher.matches()) {
                String key = matcher.group(1);
                int delayMs = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 50;
                return toolCall(ConcurrentLookupRail.TOOL_NAME, Map.of("key", key, "delayMs", delayMs));
            }
        }
        return AssistantMessage.builder().role("assistant")
            .content("Mock LLM received: " + userRequest)
            .build();
    }

    /**
     * Returns the latest user message content in the conversation list.
     *
     * @param messages converted chat messages
     * @return latest user content or an empty string
     */
    static String latestUserContent(List<Map<String, Object>> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if ("user".equals(String.valueOf(message.get("role")))) {
                return String.valueOf(message.get("content"));
            }
        }
        return "";
    }

    /**
     * Returns the latest tool result after the most recent user turn, if any.
     *
     * @param messages converted chat messages
     * @return tool result content or {@code null}
     */
    static String latestToolResultForCurrentTurn(List<Map<String, Object>> messages) {
        int lastUserIndex = -1;
        for (int index = 0; index < messages.size(); index++) {
            if ("user".equals(String.valueOf(messages.get(index).get("role")))) {
                lastUserIndex = index;
            }
        }
        if (lastUserIndex < 0) {
            return null;
        }
        for (int index = messages.size() - 1; index > lastUserIndex; index--) {
            Map<String, Object> message = messages.get(index);
            if ("tool".equals(String.valueOf(message.get("role")))) {
                return String.valueOf(message.get("content"));
            }
        }
        return null;
    }

    private static AssistantMessage toolCall(String toolName, Map<String, Object> arguments) {
        ToolCall toolCall = ToolCall.builder()
            .id("mock-" + toolName + "-" + System.nanoTime())
            .type("function")
            .name(toolName)
            .arguments(GSON.toJson(arguments))
            .index(0)
            .build();
        return AssistantMessage.builder().role("assistant").content("").toolCalls(List.of(toolCall)).build();
    }
}
