/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryMessage;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges runtime {@link MemoryStore} into core {@link MemoryProvider} lifecycle hooks.
 *
 * <p>Core owns the timing of prefetch/writeback. Runtime owns the governed memory service
 * implementation. This adapter maps core prefetch to {@link MemoryStore#search(MemorySearchRequest)}
 * and core turn sync to {@link MemoryStore#add(MemoryAddRequest)}.
 *
 * @since 0.1.0
 */
public class MemoryStoreMemoryProvider extends MemoryProvider {
    private static final int DEFAULT_PREFETCH_TOP_K = 5;

    private final MemoryStore memoryStore;

    private final MiddlewareProperties.Memory memory;

    private volatile boolean hasInitialized;

    /**
     * Creates a memory provider bridge.
     *
     * @param memoryStore runtime memory store
     * @param memory memory middleware configuration
     */
    public MemoryStoreMemoryProvider(MemoryStore memoryStore, MiddlewareProperties.Memory memory) {
        this.memoryStore = memoryStore;
        this.memory = memory != null ? memory : new MiddlewareProperties.Memory();
    }

    @Override
    public String getName() {
        if (memoryStore == null || memoryStore.getProvider() == null || memoryStore.getProvider().isBlank()) {
            return "memory-store";
        }
        return "memory-store-" + memoryStore.getProvider();
    }

    @Override
    public boolean isAvailable() {
        return memoryStore != null && memoryStore.isAvailable();
    }

    @Override
    public CompletableFuture<Void> initialize(Map<String, Object> kwargs) {
        hasInitialized = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of();
    }

    @Override
    public CompletableFuture<String> handleToolCall(String toolName, Map<String, Object> args) {
        return CompletableFuture.completedFuture(
            "{\"error\":\"MemoryStoreMemoryProvider does not expose memory tools\"}");
    }

    @Override
    public CompletableFuture<String> prefetch(String query, Map<String, Object> kwargs) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return CompletableFuture.completedFuture("");
        }
        MemorySearchRequest request = new MemorySearchRequest(resolveScope(kwargs), query, DEFAULT_PREFETCH_TOP_K,
            memory.isRerank(), options("prefetch", kwargs));
        List<MemoryRecord> records = memoryStore.search(request);
        return CompletableFuture.completedFuture(formatPrefetch(applyRelevanceFloor(records)));
    }

    @Override
    public CompletableFuture<Void> syncTurn(String userMsg, String assistantMsg, Map<String, Object> kwargs) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        List<MemoryMessage> messages = new ArrayList<>();
        addMessage(messages, "user", userMsg);
        addMessage(messages, "assistant", assistantMsg);
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> options = options("sync_turn", kwargs);
        options.put("infer", true);
        memoryStore.add(new MemoryAddRequest(resolveScope(kwargs), messages, options));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isInitialized() {
        return hasInitialized;
    }

    private String formatPrefetch(List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (MemoryRecord record : records) {
            if (record != null && record.memory() != null && !record.memory().isBlank()) {
                lines.add("- " + record.memory().trim());
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "## Long-term Memory\n" + String.join("\n", lines);
    }

    /**
     * Drops scored records below the relative relevance floor ({@code ratio × top score})
     * so low-relevance memories are not injected into the prompt — and re-written back by
     * the following turn's sync, which would amplify the noise. The ratio is relative to
     * the top hit, so it is independent of the provider's score scale; unscored records
     * cannot be judged and are kept; {@code 0} disables the floor entirely.
     *
     * @param records the search results, may be null
     * @return the records at or above the floor, or all records when none carries a score
     */
    private List<MemoryRecord> applyRelevanceFloor(List<MemoryRecord> records) {
        double ratio = memory.getPrefetchMinScoreRatio();
        if (ratio <= 0 || records == null || records.isEmpty()) {
            return records;
        }
        Double topScore = null;
        for (MemoryRecord record : records) {
            if (record == null) {
                continue;
            }
            Double score = scoreOf(record);
            if (score != null && (topScore == null || score > topScore)) {
                topScore = score;
            }
        }
        if (topScore == null) {
            return records;
        }
        double floor = topScore * ratio;
        List<MemoryRecord> kept = new ArrayList<>();
        for (MemoryRecord record : records) {
            Double score = record == null ? null : scoreOf(record);
            if (score == null || score >= floor) {
                kept.add(record);
            }
        }
        return kept;
    }

    private static Double scoreOf(MemoryRecord record) {
        Object score = record.metadata() != null ? record.metadata().get("score") : null;
        return score instanceof Number number ? number.doubleValue() : null;
    }

    private MemoryScope resolveScope(Map<String, Object> kwargs) {
        return new MemoryScope(
            firstText(kwargs, "", "user_id", "userId"),
            firstText(kwargs, "", "agent_id", "agentId"),
            firstText(kwargs, "", "session_id", "sessionId"),
            firstText(kwargs, "", "scope_id", "scopeId", "space_id", "spaceId"));
    }

    private Map<String, Object> options(String source, Map<String, Object> kwargs) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("source", source);
        copyIfPresent(options, kwargs, "conversation_id");
        copyIfPresent(options, kwargs, "tenant_id");
        return options;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source != null && source.get(key) != null && !String.valueOf(source.get(key)).isBlank()) {
            target.put(key, source.get(key));
        }
    }

    private String firstText(Map<String, Object> kwargs, String defaultValue, String... keys) {
        if (kwargs != null) {
            for (String key : keys) {
                Object value = kwargs.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
        }
        return defaultValue != null ? defaultValue.trim() : "";
    }

    private void addMessage(List<MemoryMessage> messages, String role, String content) {
        if (content != null && !content.isBlank()) {
            messages.add(new MemoryMessage(role, content));
        }
    }
}
