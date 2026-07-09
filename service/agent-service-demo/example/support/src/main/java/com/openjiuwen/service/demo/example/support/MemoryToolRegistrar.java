/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.Result;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryMessage;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers provider-agnostic memory tools on a demo {@link ReActAgent}.
 *
 * <p>Tool names and parameters are generic ({@code memory_search}, {@code memory_add}, etc.) while
 * calls route through the runtime {@link MemoryStore} SPI. Pass {@code includeManagementTools=true}
 * to also expose {@code memory_get} / {@code memory_delete}.
 *
 * @since 0.1.0
 */
public final class MemoryToolRegistrar {
    private static final String TOOL_SEARCH = "memory_search";
    private static final String TOOL_ADD = "memory_add";
    private static final String TOOL_GET = "memory_get";
    private static final String TOOL_DELETE = "memory_delete";

    private static final Map<String, Object> SEARCH_SCHEMA = Map.of(
        "name", TOOL_SEARCH,
        "description", "Search memories by meaning.",
        "parameters", Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "What to search for."),
                "rerank", Map.of("type", "boolean", "description", "Enable reranking."),
                "top_k", Map.of("type", "integer", "description", "Max results.")
            ),
            "required", List.of("query")
        )
    );

    private static final Map<String, Object> ADD_SCHEMA = Map.of(
        "name", TOOL_ADD,
        "description", "Store a durable fact about the user.",
        "parameters", Map.of(
            "type", "object",
            "properties", Map.of("content", Map.of("type", "string", "description", "The fact to store.")),
            "required", List.of("content")
        )
    );

    private static final Map<String, Object> GET_SCHEMA = Map.of(
        "name", TOOL_GET,
        "description", "Fetch a single memory record by id (management).",
        "parameters", Map.of(
            "type", "object",
            "properties", Map.of(
                "memory_id", Map.of("type", "string", "description", "The memory record id.")
            ),
            "required", List.of("memory_id")
        )
    );

    private static final Map<String, Object> DELETE_SCHEMA = Map.of(
        "name", TOOL_DELETE,
        "description", "Delete a single memory record by id (destructive, management).",
        "parameters", Map.of(
            "type", "object",
            "properties", Map.of(
                "memory_id", Map.of("type", "string", "description", "The memory record id to delete.")
            ),
            "required", List.of("memory_id")
        )
    );

    private MemoryToolRegistrar() {
    }

    /**
     * Registers standard memory tools.
     *
     * @param agent memory demo agent
     * @param memoryStore governed runtime memory store
     * @return registered tool cards
     */
    public static List<ToolCard> register(ReActAgent agent, MemoryStore memoryStore) {
        return register(agent, memoryStore, false);
    }

    /**
     * Registers memory tools, optionally including management tools.
     *
     * @param agent memory demo agent
     * @param memoryStore governed runtime memory store
     * @param includeManagementTools when {@code true}, also register {@code memory_get} and
     *        {@code memory_delete}
     * @return registered tool cards
     */
    public static List<ToolCard> register(ReActAgent agent, MemoryStore memoryStore,
        boolean includeManagementTools) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (memoryStore == null) {
            return List.of();
        }

        List<ToolCard> cards = new ArrayList<>();
        registerSchemaTool(agent, memoryStore, SEARCH_SCHEMA, cards);
        registerSchemaTool(agent, memoryStore, ADD_SCHEMA, cards);
        if (includeManagementTools) {
            registerSchemaTool(agent, memoryStore, GET_SCHEMA, cards);
            registerSchemaTool(agent, memoryStore, DELETE_SCHEMA, cards);
        }
        return cards;
    }

    private static void registerSchemaTool(ReActAgent agent, MemoryStore memoryStore, Map<String, Object> schema,
        List<ToolCard> cards) {
        String toolName = String.valueOf(schema.getOrDefault("name", ""));
        if (toolName.isBlank()) {
            return;
        }
        String toolId = "external_memory_" + memoryStore.getProvider() + "_" + toolName;
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters =
            schema.get("parameters") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        ToolCard card = ToolCard.builder()
            .id(toolId)
            .name(toolName)
            .description(String.valueOf(schema.getOrDefault("description", "")))
            .inputParams(parameters)
            .build();
        LocalFunction tool = new LocalFunction(card, (inputs, kwargs) ->
            invokeTool(memoryStore, toolName, inputs, resolveScope(kwargs)));
        registerTool(agent, tool);
        cards.add(card);
    }

    private static void registerTool(ReActAgent agent, LocalFunction tool) {
        Result<ToolCard> result = Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true);
        if (result.isError()) {
            throw new IllegalStateException(
                "Failed to register memory tool " + tool.getCard().getName(),
                result.getError());
        }
        agent.getAbilityManager().add(tool.getCard());
    }

    private static Object invokeTool(MemoryStore memoryStore, String toolName, Map<String, Object> inputs,
        MemoryScope scope) {
        try {
            if (TOOL_SEARCH.equals(toolName)) {
                return invokeSearch(memoryStore, inputs, scope);
            }
            if (TOOL_ADD.equals(toolName)) {
                return invokeAdd(memoryStore, inputs, scope);
            }
            if (TOOL_GET.equals(toolName)) {
                return invokeGet(memoryStore, inputs, scope);
            }
            if (TOOL_DELETE.equals(toolName)) {
                return invokeDelete(memoryStore, inputs, scope);
            }
            return Map.of("error", "Unknown tool: " + toolName);
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage());
        }
    }

    private static Map<String, Object> invokeSearch(MemoryStore memoryStore, Map<String, Object> inputs,
        MemoryScope scope) {
        String query = requiredString(inputs, "query");
        int topK = intValue(inputs != null ? inputs.get("top_k") : null, 10);
        Boolean rerank = inputs != null && inputs.get("rerank") != null
            ? Boolean.parseBoolean(String.valueOf(inputs.get("rerank")))
            : null;
        List<MemoryRecord> records = memoryStore.search(new MemorySearchRequest(
            scope, query, topK, rerank, Map.of()));
        return Map.of("results", records.stream().map(MemoryToolRegistrar::toToolRecord).toList(),
            "count", records.size());
    }

    private static Map<String, Object> invokeAdd(MemoryStore memoryStore, Map<String, Object> inputs,
        MemoryScope scope) {
        String content = requiredString(inputs, "content");
        MemoryWriteResult result = memoryStore.add(new MemoryAddRequest(
            scope,
            List.of(new MemoryMessage("user", content)),
            Map.of("infer", false)));
        return Map.of("result", "Fact stored.", "count", result.records().size());
    }

    private static Map<String, Object> invokeGet(MemoryStore memoryStore, Map<String, Object> inputs,
        MemoryScope scope) {
        String memoryId = requiredMemoryId(inputs);
        return memoryStore.get(new MemoryGetRequest(scope, memoryId))
            .map(MemoryToolRegistrar::toToolRecord)
            .orElseGet(() -> Map.of("error", "Memory not found: " + memoryId));
    }

    private static Map<String, Object> invokeDelete(MemoryStore memoryStore, Map<String, Object> inputs,
        MemoryScope scope) {
        String memoryId = requiredMemoryId(inputs);
        memoryStore.delete(new MemoryDeleteRequest(scope, memoryId));
        return Map.of("result", "Memory deleted.", "memory_id", memoryId);
    }

    private static MemoryScope resolveScope(Map<String, Object> kwargs) {
        Object rawSession = kwargs != null ? kwargs.get("session") : null;
        String userId = "";
        String agentId = "";
        String sessionId = "";
        String scopeId = "";
        if (rawSession instanceof AgentSessionApi session) {
            userId = stringValue(session.getEnv("user_id", ""));
            agentId = stringValue(session.getEnv("agent_id", ""));
            sessionId = stringValue(session.getSessionId());
            scopeId = stringValue(session.getEnv("space_id", ""));
        } else if (rawSession instanceof Session session) {
            sessionId = stringValue(session.getSessionId());
        }
        return new MemoryScope(userId, agentId, sessionId, scopeId);
    }

    private static String requiredMemoryId(Map<String, Object> inputs) {
        Object value = inputs != null ? inputs.get("memory_id") : null;
        String memoryId = value != null ? String.valueOf(value).trim() : "";
        if (memoryId.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: memory_id");
        }
        return memoryId;
    }

    private static String requiredString(Map<String, Object> inputs, String key) {
        Object value = inputs != null ? inputs.get(key) : null;
        String text = value != null ? String.valueOf(value).trim() : "";
        if (text.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return text;
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static Map<String, Object> toToolRecord(MemoryRecord record) {
        Map<String, Object> result = new LinkedHashMap<>(record.raw());
        if (!record.memoryId().isBlank()) {
            result.putIfAbsent("memory_id", record.memoryId());
        }
        if (!record.memory().isBlank()) {
            result.putIfAbsent("memory", record.memory());
        }
        return result;
    }
}
