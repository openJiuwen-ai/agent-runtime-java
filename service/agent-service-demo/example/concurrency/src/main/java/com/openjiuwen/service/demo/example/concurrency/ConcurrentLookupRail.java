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
 * Simulates concurrent skill lookup with configurable latency.
 *
 * @since 0.1.0
 */
public class ConcurrentLookupRail extends BaseInterruptRail {
    /** Registered tool name for the concurrent lookup rail. */
    public static final String TOOL_NAME = "concurrent_lookup";

    static final String RESULT_PREFIX = "LOOKUP:";

    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final int DEFAULT_DELAY_MS = 50;

    /**
     * Registers the lookup tool card on this rail.
     */
    public ConcurrentLookupRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
            .description("Lookup a key with simulated latency for concurrent tool validation")
            .inputParams(Map.of("type", "object", "properties", Map.of("key",
                Map.of("type", "string", "description", "Lookup key"), "delayMs",
                Map.of("type", "integer", "description", "Optional simulated delay in milliseconds")),
                "required", List.of("key")))
            .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        String key = extractKey(toolCall);
        int delayMs = extractDelayMs(toolCall);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return reject(RESULT_PREFIX + key + ":done");
    }

    /**
     * Parses the lookup key from tool call arguments.
     *
     * @param toolCall invoked tool call
     * @return trimmed key or a fallback token
     */
    static String extractKey(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object key = args.get("key");
            if (key instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // fall through
        }
        return "missing-key";
    }

    /**
     * Parses optional simulated delay from tool call arguments.
     *
     * @param toolCall invoked tool call
     * @return delay in milliseconds, never negative
     */
    static int extractDelayMs(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object delay = args.get("delayMs");
            if (delay instanceof Number number) {
                return Math.max(0, number.intValue());
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // fall through
        }
        return DEFAULT_DELAY_MS;
    }
}
