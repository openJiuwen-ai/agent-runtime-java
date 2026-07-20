/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen;

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
 * {@link MemoryStore} implementation backed by the Jiuwen Memory Engine API.
 *
 * <p>API mapping:
 * <ul>
 *   <li>{@code add} → {@code POST /add_messages/}</li>
 *   <li>{@code search} → {@code POST /search_memory/}</li>
 *   <li>{@code get} → {@code POST /get_user_mem_by_page/} + filter by mem_id</li>
 *   <li>{@code delete} → not supported (API only supports delete-by-scope)</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class JiuwenMemoryStore implements MemoryStore {
    private static final String PROVIDER = "jiuwen";
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final int GET_PAGE_SIZE = 50;
    private static final int GET_MAX_PAGES = 10;

    private final String apiKey;
    private final String baseUrl;
    private final MemoryScope defaultScope;
    private final JiuwenMemoryApi api;

    public JiuwenMemoryStore(String apiKey, MiddlewareProperties.Memory memory, JiuwenMemoryApi api) {
        MiddlewareProperties.Memory config = memory != null ? memory : new MiddlewareProperties.Memory();
        this.apiKey = apiKey != null ? apiKey : "";
        this.baseUrl = config.getEndpoint();
        this.defaultScope = new MemoryScope(config.getUserId(), "", "", "");
        this.api = api != null ? api
            : new JiuwenMemoryApi(config.getEndpoint(), config, this.apiKey);
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
        List<Map<String, Object>> messages = toJiuwenMessages(normalized.messages());
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("memory add messages must not be empty");
        }
        MemoryScope scope = mergeScope(normalized.scope());
        Map<String, Object> response = api.addMessages(baseUrl, messages,
            scope.userId(), scope.scopeId());
        return new MemoryWriteResult(List.of(), response);
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
        MemoryScope scope = mergeScope(normalized.scope());
        List<Map<String, Object>> records = api.searchMemory(baseUrl, normalized.query(), topK,
            scope.userId(), scope.scopeId());
        return toRecords(records);
    }

    @Override
    public Optional<MemoryRecord> get(MemoryGetRequest request) {
        ensureAvailable();
        MemoryGetRequest normalized = request != null ? request : new MemoryGetRequest(MemoryScope.empty(), "");
        if (normalized.memoryId().isBlank()) {
            return Optional.empty();
        }
        MemoryScope scope = mergeScope(normalized.scope());
        // Memory Engine API has no direct get-by-id; iterate pages to find the record
        for (int page = 1; page <= GET_MAX_PAGES; page++) {
            List<Map<String, Object>> records = api.getUserMemByPage(baseUrl,
                scope.userId(), scope.scopeId(), GET_PAGE_SIZE, page);
            if (records.isEmpty()) {
                break;
            }
            for (Map<String, Object> record : records) {
                String memId = stringValue(record.get("mem_id"));
                if (normalized.memoryId().equals(memId)) {
                    return Optional.of(toRecord(record));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(MemoryDeleteRequest request) {
        throw new UnsupportedOperationException(
            "Jiuwen Memory Engine API does not support delete-by-memory-id; only delete-by-scope is available");
    }

    // --- Private helpers ---

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("Jiuwen Memory Store is not available: API key is blank");
        }
    }

    private MemoryScope mergeScope(MemoryScope requestScope) {
        if (requestScope == null) {
            return defaultScope;
        }
        String userId = requestScope.userId().isBlank() ? defaultScope.userId() : requestScope.userId();
        String agentId = requestScope.agentId().isBlank() ? defaultScope.agentId() : requestScope.agentId();
        String sessionId = requestScope.sessionId().isBlank() ? defaultScope.sessionId() : requestScope.sessionId();
        String scopeId = requestScope.scopeId().isBlank() ? defaultScope.scopeId() : requestScope.scopeId();
        return new MemoryScope(userId, agentId, sessionId, scopeId);
    }

    private List<Map<String, Object>> toJiuwenMessages(List<MemoryMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (MemoryMessage msg : messages) {
            if (msg.content().isBlank()) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", msg.role());
            map.put("content", msg.content());
            result.add(map);
        }
        return result;
    }

    private int normalizeTopK(int topK) {
        if (topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<MemoryRecord> toRecords(List<Map<String, Object>> records) {
        List<MemoryRecord> result = new ArrayList<>();
        if (records == null) {
            return result;
        }
        for (Map<String, Object> record : records) {
            result.add(toRecord(record));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private MemoryRecord toRecord(Map<String, Object> raw) {
        String memId = stringValue(raw.get("mem_id"));
        String content = stringValue(raw.get("content"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object type = raw.get("type");
        if (type != null) {
            metadata.put("type", type);
        }
        Object score = raw.get("score");
        if (score != null) {
            metadata.put("score", score);
        }
        return new MemoryRecord(memId, content, metadata, raw);
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
