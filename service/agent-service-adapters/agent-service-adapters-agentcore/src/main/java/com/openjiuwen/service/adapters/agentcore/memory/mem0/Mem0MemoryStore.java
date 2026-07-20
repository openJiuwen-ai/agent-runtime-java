/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.mem0;

import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryMessage;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime {@link MemoryStore} implementation backed by mem0.
 *
 * <p>The store uses runtime governance through {@link GovernedMem0Api}. It intentionally
 * stays below Agent/tool semantics; callers decide when to add, search, get, or delete.
 *
 * @since 0.1.0
 */
public class Mem0MemoryStore implements MemoryStore {
    private static final String PROVIDER = "mem0";

    private static final int DEFAULT_TOP_K = 10;

    private static final int MAX_TOP_K = 50;

    private final String apiKey;

    private final String baseUrl;

    private final MemoryScope defaultScope;

    private final boolean shouldRerankByDefault;

    private final GovernedMem0Api api;

    /**
     * Creates a mem0 memory store.
     *
     * @param apiKey decrypted mem0 API key
     * @param memory memory configuration
     * @param api governed mem0 transport
     */
    public Mem0MemoryStore(String apiKey, MiddlewareProperties.Memory memory, GovernedMem0Api api) {
        MiddlewareProperties.Memory config = memory != null ? memory : new MiddlewareProperties.Memory();
        this.apiKey = apiKey != null ? apiKey : "";
        this.baseUrl = config.getEndpoint();
        this.defaultScope = new MemoryScope(config.getUserId(), "", "", "");
        this.shouldRerankByDefault = config.isRerank();
        this.api = api != null ? api
            : new GovernedMem0Api(config.getEndpoint(), config, config.getAuthHeaderMode(), this.apiKey,
                config.getPathStyle());
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    @Override
    public MemoryWriteResult add(MemoryAddRequest request) {
        ensureAvailable();
        MemoryAddRequest normalized = request != null
            ? request
            : new MemoryAddRequest(MemoryScope.empty(), List.of(), Map.of());
        List<Map<String, Object>> messages = toMem0Messages(normalized.messages());
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("memory add messages must not be empty");
        }
        Object inferOption = normalized.options().get("infer");
        boolean shouldInfer = inferOption != null && Boolean.parseBoolean(String.valueOf(inferOption));
        List<Map<String, Object>> records =
            api.addMemoryRecords(baseUrl, apiKey, messages, toMem0Scope(normalized.scope()), shouldInfer);
        return new MemoryWriteResult(toRecords(records), Map.of("results", records));
    }

    @Override
    public List<MemoryRecord> search(MemorySearchRequest request) {
        ensureAvailable();
        MemorySearchRequest normalized = request != null
            ? request
            : new MemorySearchRequest(MemoryScope.empty(), "", 0, null, Map.of());
        if (normalized.query().isBlank()) {
            return List.of();
        }
        int topK = normalizeTopK(normalized.topK());
        boolean shouldRerank = normalized.shouldRerank() != null ? normalized.shouldRerank() : shouldRerankByDefault;
        GovernedMem0Api.SearchMemoriesOptions options =
            GovernedMem0Api.SearchMemoriesOptions.of(toMem0Scope(normalized.scope()), shouldRerank, topK);
        List<Map<String, Object>> records = api.searchMemories(baseUrl, apiKey, normalized.query(), options);
        return toRecords(records);
    }

    @Override
    public Optional<MemoryRecord> get(MemoryGetRequest request) {
        ensureAvailable();
        MemoryGetRequest normalized = request != null ? request : new MemoryGetRequest(MemoryScope.empty(), "");
        if (normalized.memoryId().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> record = api.getMemory(baseUrl, apiKey, normalized.memoryId());
        if (record == null || record.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toRecord(record));
    }

    @Override
    public void delete(MemoryDeleteRequest request) {
        ensureAvailable();
        MemoryDeleteRequest normalized = request != null
            ? request
            : new MemoryDeleteRequest(MemoryScope.empty(), "");
        if (normalized.memoryId().isBlank()) {
            throw new IllegalArgumentException("memory_id must not be blank");
        }
        api.deleteMemory(baseUrl, apiKey, normalized.memoryId());
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("mem0 memory store is unavailable because api key is empty");
        }
    }

    private List<Map<String, Object>> toMem0Messages(List<MemoryMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MemoryMessage message : messages != null ? messages : List.<MemoryMessage>of()) {
            if (message == null || message.content().isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role().isBlank() ? "user" : message.role());
            item.put("content", message.content());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> toMem0Scope(MemoryScope scope) {
        MemoryScope effective = mergeScope(scope);
        Map<String, Object> result = new LinkedHashMap<>();
        if (!effective.userId().isBlank()) {
            result.put("user_id", effective.userId());
        }
        if (!effective.agentId().isBlank()) {
            result.put("agent_id", effective.agentId());
        }
        return result;
    }

    private MemoryScope mergeScope(MemoryScope scope) {
        MemoryScope request = scope != null ? scope : MemoryScope.empty();
        return new MemoryScope(
            !request.userId().isBlank() ? request.userId() : defaultScope.userId(),
            request.agentId(),
            !request.sessionId().isBlank() ? request.sessionId() : defaultScope.sessionId(),
            !request.scopeId().isBlank() ? request.scopeId() : defaultScope.scopeId());
    }

    private int normalizeTopK(int topK) {
        if (topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<MemoryRecord> toRecords(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<MemoryRecord> result = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (record != null && !record.isEmpty()) {
                result.add(toRecord(record));
            }
        }
        return result;
    }

    private MemoryRecord toRecord(Map<String, Object> record) {
        Map<String, Object> raw = new LinkedHashMap<>(record);
        String memoryId = stringValue(firstPresent(raw, "memory_id", "id"));
        String memory = stringValue(firstPresent(raw, "memory", "text"));
        Map<String, Object> metadata = raw.get("metadata") instanceof Map<?, ?> map ? castMap(map) : Map.of();
        return new MemoryRecord(memoryId, memory, metadata, raw);
    }

    private Object firstPresent(Map<String, Object> record, String first, String second) {
        Object value = record.get(first);
        return value != null ? value : record.get(second);
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
