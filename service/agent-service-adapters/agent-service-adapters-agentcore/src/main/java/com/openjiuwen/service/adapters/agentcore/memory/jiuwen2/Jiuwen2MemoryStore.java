/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen2;

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
 * {@link MemoryStore} implementation backed by the agent-memory 2.0 API.
 *
 * <p>API mapping (verified against agent-memory 0.2.0 beta1):
 * <ul>
 *   <li>{@code add} → {@code POST /v1/add} — the turn's messages are merged into a
 *       single content with {@code [user]}/{@code [assistant]} markers (turn-level
 *       ingestion, mirroring the mem0 best practice)</li>
 *   <li>{@code search} → {@code POST /v1/search} with explicit {@code disclosure=l2}
 *       (the 2.0 default l0 returns abstracts only)</li>
 *   <li>{@code get} → {@code POST /v1/get}; 404 maps to empty</li>
 *   <li>{@code delete} → {@code POST /v1/delete} by unit id</li>
 * </ul>
 *
 * <p>Scope mapping: 2.0 authorizes the target scope against the caller identity, so
 * {@code scope.org}/{@code scope.user} come from configuration
 * ({@code scope-org}/{@code scope-user}, matching the server identity in dev mode)
 * instead of the business caller. The business end-user/agent/scope dimensions are
 * preserved in {@code user_metadata}; the business session id maps to
 * {@code scope.session}, which the server does not validate against the identity.
 *
 * @since 0.1.0
 */
public class Jiuwen2MemoryStore implements MemoryStore {
    private static final String PROVIDER = "jiuwen2";
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;

    private final String apiKey;
    private final String baseUrl;
    private final String scopeOrg;
    private final String scopeUser;
    private final Jiuwen2MemoryApi api;

    public Jiuwen2MemoryStore(String apiKey, MiddlewareProperties.Memory memory, Jiuwen2MemoryApi api) {
        MiddlewareProperties.Memory config = memory != null ? memory : new MiddlewareProperties.Memory();
        this.apiKey = apiKey != null ? apiKey : "";
        this.baseUrl = config.getEndpoint();
        this.scopeOrg = config.getScopeOrg();
        this.scopeUser = config.getScopeUser();
        this.api = api != null ? api
            : new Jiuwen2MemoryApi(config.getEndpoint(), config, this.apiKey);
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
        String content = mergeTurnContent(normalized.messages());
        if (content.isBlank()) {
            throw new IllegalArgumentException("memory add messages must not be empty");
        }
        MemoryScope scope = normalized.scope();
        Map<String, Object> responseScope = requestScope(scope);
        Map<String, Object> userMetadata = businessMetadata(scope);
        List<Map<String, Object>> units = api.add(baseUrl, content, responseScope, userMetadata);
        return new MemoryWriteResult(toRecords(units, "id"), Map.of());
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
        List<Map<String, Object>> items = api.search(baseUrl, normalized.query(),
            requestScope(normalized.scope()), topK);
        return toRecords(items, "unit_id");
    }

    @Override
    public Optional<MemoryRecord> get(MemoryGetRequest request) {
        ensureAvailable();
        MemoryGetRequest normalized = request != null ? request : new MemoryGetRequest(MemoryScope.empty(), "");
        if (normalized.memoryId().isBlank()) {
            return Optional.empty();
        }
        return api.get(baseUrl, normalized.memoryId(), requestScope(normalized.scope()))
            .map(unit -> toRecord(unit, "id"));
    }

    @Override
    public void delete(MemoryDeleteRequest request) {
        ensureAvailable();
        if (request == null || request.memoryId().isBlank()) {
            throw new IllegalArgumentException("memory_id must not be blank");
        }
        api.delete(baseUrl, request.memoryId(), requestScope(request.scope()));
    }

    // --- Private helpers ---

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("Jiuwen2 Memory Store is not available: API key is blank");
        }
    }

    /**
     * Builds the 2.0 request scope. The {@code org}/{@code user} dimensions must match
     * the server-side caller identity (dev mode: fixed identity), so they come from
     * configuration; the business session id is accepted on the {@code session}
     * dimension; {@code space}/{@code agent} stay empty — a non-empty unregistered
     * space or a foreign agent id is rejected by the server.
     *
     * @param requestScope the request scope carrying the business session id
     * @return the 2.0 request scope map (org/space/user/agent/session)
     */
    private Map<String, Object> requestScope(MemoryScope requestScope) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("org", scopeOrg);
        scope.put("space", "");
        scope.put("user", scopeUser);
        scope.put("agent", "");
        scope.put("session", requestScope != null ? requestScope.sessionId() : "");
        return scope;
    }

    /**
     * Preserves the business scope dimensions that cannot be expressed on the 2.0
     * scope (which is identity-bound) as user metadata on the stored unit.
     *
     * @param requestScope the business scope to preserve
     * @return the user metadata map carrying the business dimensions
     */
    private Map<String, Object> businessMetadata(MemoryScope requestScope) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (requestScope == null) {
            return metadata;
        }
        putIfPresent(metadata, "biz_user_id", requestScope.userId());
        putIfPresent(metadata, "biz_agent_id", requestScope.agentId());
        putIfPresent(metadata, "biz_scope_id", requestScope.scopeId());
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Merges the turn's messages into a single content with role markers, matching the
     * turn-level ingestion the 2.0 single-content {@code add} contract expects.
     *
     * @param messages the turn's messages, may be null
     * @return the merged content with role markers, blank when no message carries text
     */
    private String mergeTurnContent(List<MemoryMessage> messages) {
        if (messages == null) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (MemoryMessage msg : messages) {
            if (msg == null || msg.content().isBlank()) {
                continue;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append('[').append(msg.role().isBlank() ? "user" : msg.role()).append("] ")
                .append(msg.content());
        }
        return content.toString();
    }

    private int normalizeTopK(int topK) {
        if (topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    /**
     * Maps raw response entries to records. The id field differs by endpoint:
     * memory units ({@code add}/{@code get}) carry {@code id}, search items carry
     * {@code unit_id}.
     *
     * @param raws the raw response entries
     * @param idField the response field carrying the record id
     * @return the mapped records, empty when the response carries no entries
     */
    private List<MemoryRecord> toRecords(List<Map<String, Object>> raws, String idField) {
        List<MemoryRecord> records = new ArrayList<>();
        if (raws == null) {
            return records;
        }
        for (Map<String, Object> raw : raws) {
            records.add(toRecord(raw, idField));
        }
        return records;
    }

    private MemoryRecord toRecord(Map<String, Object> raw, String idField) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object score = raw.get("score");
        if (score != null) {
            metadata.put("score", score);
        }
        Object level = raw.get("level");
        if (level != null) {
            metadata.put("level", level);
        }
        Object tier = raw.get("tier");
        if (tier != null) {
            metadata.put("tier", tier);
        }
        Object lifecycle = raw.get("lifecycle");
        if (lifecycle != null) {
            metadata.put("lifecycle", lifecycle);
        }
        String memoryId = stringValue(raw.get(idField));
        return new MemoryRecord(memoryId, unitContent(raw), metadata, raw);
    }

    /**
     * Resolves the unit text. Search items carry a top-level {@code content}; memory
     * units (add/get) hold the text in {@code segments[].content} and only expose
     * abstract/overview on {@code layers}.
     *
     * @param raw the raw response entry
     * @return the resolved unit text, blank when the entry carries no text
     */
    private static String unitContent(Map<String, Object> raw) {
        String content = stringValue(raw.get("content"));
        if (content.isBlank()) {
            content = joinedSegmentContent(raw.get("segments"));
        }
        if (content.isBlank()) {
            content = stringValue(raw.get("overview"));
        }
        if (content.isBlank()) {
            content = stringValue(raw.get("abstract"));
        }
        return content;
    }

    /**
     * Joins the non-blank {@code segments[].content} values with newlines.
     *
     * @param segments the raw segments value of a memory unit
     * @return the joined segment text, blank when the unit has no segments
     */
    private static String joinedSegmentContent(Object segments) {
        if (!(segments instanceof List<?> list)) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (Object item : list) {
            appendSegment(joined, item);
        }
        return joined.toString();
    }

    /**
     * Appends one segment's text, skipping blank ones and joining with newlines.
     *
     * @param joined the builder collecting segment texts
     * @param item the raw segment entry
     */
    private static void appendSegment(StringBuilder joined, Object item) {
        if (!(item instanceof Map<?, ?> segment)) {
            return;
        }
        String text = stringValue(segment.get("content"));
        if (text.isBlank()) {
            return;
        }
        if (joined.length() > 0) {
            joined.append('\n');
        }
        joined.append(text);
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
