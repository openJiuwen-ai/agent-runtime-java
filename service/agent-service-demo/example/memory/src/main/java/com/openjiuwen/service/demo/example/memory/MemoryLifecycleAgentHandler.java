/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo-only lifecycle bridge: prefetch before a request and sync the turn after it.
 *
 * <p>This keeps lifecycle memory separate from LLM tool calling. The lifecycle path uses Core's
 * {@link MemoryProvider}; the ReAct tool path is still registered separately against Runtime
 * {@code MemoryStore}.
 *
 * @since 0.1.0
 */
final class MemoryLifecycleAgentHandler implements AgentHandler {
    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleAgentHandler.class);

    private static final String MEMORY_CONTEXT_OPEN = "<memory-context>";

    private static final String MEMORY_CONTEXT_CLOSE = "</memory-context>";

    private static final String USER_MESSAGE_OPEN = "<user-message>";

    private static final String USER_MESSAGE_CLOSE = "</user-message>";

    private final AgentHandler delegate;

    private final MemoryStoreMemoryProvider memoryProvider;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    MemoryLifecycleAgentHandler(AgentHandler delegate, MemoryStoreMemoryProvider memoryProvider) {
        this.delegate = delegate;
        this.memoryProvider = memoryProvider;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String originalUserQuery = request != null ? request.lastUserQuery() : "";
        Map<String, Object> scope = memoryScope(request);
        ServeRequest effectiveRequest = withPrefetchedMemory(request, originalUserQuery, scope);
        QueryResponse response = delegate.query(effectiveRequest);
        syncTurn(originalUserQuery, assistantText(response), scope);
        return response;
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String originalUserQuery = request != null ? request.lastUserQuery() : "";
        Map<String, Object> scope = memoryScope(request);
        ServeRequest effectiveRequest = withPrefetchedMemory(request, originalUserQuery, scope);
        StringBuilder assistant = new StringBuilder();
        delegate.streamQuery(effectiveRequest, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                appendChunkText(chunk, assistant);
                observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                observer.onError(error);
            }

            @Override
            public void onComplete() {
                syncTurn(originalUserQuery, assistant.toString(), scope);
                observer.onComplete();
            }

            @Override
            public boolean isCancelled() {
                return observer.isCancelled();
            }
        });
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void clearSession(String conversationId) {
        delegate.clearSession(conversationId);
    }

    private ServeRequest withPrefetchedMemory(ServeRequest request, String query, Map<String, Object> scope) {
        String memoryContext = prefetch(query, scope);
        if (memoryContext.isBlank()) {
            return request;
        }
        ServeRequest copy = copyRequest(request);
        copy.setMessages(enrichLastUserMessage(copy.getMessages(), memoryContext, query));
        return copy;
    }

    private String prefetch(String query, Map<String, Object> scope) {
        if (memoryProvider == null || !memoryProvider.isAvailable() || query == null || query.isBlank()) {
            return "";
        }
        try {
            initializeProvider(scope);
            String memoryContext = memoryProvider.prefetch(query, scope);
            return memoryContext != null ? memoryContext.trim() : "";
        } catch (ExternalSvcAdapterException | IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException ex) {
            log.warn("Memory prefetch failed and will be skipped: {}", ex.getMessage());
            return "";
        }
    }

    private void syncTurn(String userQuery, String assistantText, Map<String, Object> scope) {
        if (memoryProvider == null || !memoryProvider.isAvailable()) {
            return;
        }
        if ((userQuery == null || userQuery.isBlank()) && (assistantText == null || assistantText.isBlank())) {
            return;
        }
        try {
            initializeProvider(scope);
            memoryProvider.syncTurn(userQuery, assistantText, scope);
        } catch (ExternalSvcAdapterException | IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException ex) {
            log.warn("Memory syncTurn failed and will be skipped: {}", ex.getMessage());
        }
    }

    private void initializeProvider(Map<String, Object> scope) {
        if (memoryProvider.isInitialized() || !initialized.compareAndSet(false, true)) {
            return;
        }
        try {
            memoryProvider.initialize(scope);
        } catch (ExternalSvcAdapterException | IllegalArgumentException | IllegalStateException
                 | UnsupportedOperationException ex) {
            initialized.set(false);
            throw ex;
        }
    }

    private Map<String, Object> memoryScope(ServeRequest request) {
        Map<String, Object> scope = new LinkedHashMap<>();
        if (request == null) {
            return scope;
        }
        if (request.getMetadata() != null) {
            scope.putAll(request.getMetadata());
        }
        putIfNotBlank(scope, "conversation_id", request.getConversationId());
        putIfNotBlank(scope, "session_id", request.getConversationId());
        putIfNotBlank(scope, "user_id", request.getUserId());
        putIfNotBlank(scope, "space_id", request.getSpaceId());
        putIfNotBlank(scope, "scope_id", request.getSpaceId());
        putIfNotBlank(scope, "tenant_id", request.getTenantId());
        return scope;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private ServeRequest copyRequest(ServeRequest source) {
        ServeRequest copy = new ServeRequest();
        if (source == null) {
            return copy;
        }
        copy.setConversationId(source.getConversationId());
        copy.setMessages(copyMessages(source.getMessages()));
        copy.setUserId(source.getUserId());
        copy.setSpaceId(source.getSpaceId());
        copy.setTenantId(source.getTenantId());
        copy.setStream(source.isStream());
        copy.setMetadata(source.getMetadata() != null ? new LinkedHashMap<>(source.getMetadata()) : Map.of());
        return copy;
    }

    private List<Map<String, Object>> copyMessages(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : source != null ? source : List.<Map<String, Object>>of()) {
            result.add(message != null ? new LinkedHashMap<>(message) : new LinkedHashMap<>());
        }
        return result;
    }

    private List<Map<String, Object>> enrichLastUserMessage(List<Map<String, Object>> messages, String memoryContext,
        String originalUserQuery) {
        List<Map<String, Object>> result = copyMessages(messages);
        for (int i = result.size() - 1; i >= 0; i--) {
            Map<String, Object> message = result.get(i);
            if ("user".equalsIgnoreCase(String.valueOf(message.get("role")))) {
                message.put("content", formatUserMessage(memoryContext, originalUserQuery));
                return result;
            }
        }
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", formatUserMessage(memoryContext, originalUserQuery));
        result.add(userMessage);
        return result;
    }

    private String formatUserMessage(String memoryContext, String originalUserQuery) {
        return MEMORY_CONTEXT_OPEN + "\n"
            + memoryContext
            + "\n" + MEMORY_CONTEXT_CLOSE
            + "\n\n" + USER_MESSAGE_OPEN + "\n"
            + (originalUserQuery != null ? originalUserQuery : "")
            + "\n" + USER_MESSAGE_CLOSE;
    }

    private String assistantText(QueryResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        Object result = response.getResult();
        if (result instanceof Map<?, ?> map) {
            Optional<Object> text = firstPresent(map, "content", "output", "response");
            return text.map(String::valueOf).orElseGet(() -> String.valueOf(result));
        }
        return String.valueOf(result);
    }

    private void appendChunkText(QueryChunk chunk, StringBuilder assistant) {
        if (chunk == null || chunk.getData() == null) {
            return;
        }
        if (chunk.getData() instanceof Map<?, ?> map) {
            Optional<Object> text = firstPresent(map, "content", "delta", "output", "response");
            Object payload = map.get("payload");
            if (payload instanceof Map<?, ?> payloadMap) {
                Optional<Object> payloadText = firstPresent(payloadMap, "content", "delta", "output", "response");
                if (payloadText.isPresent()) {
                    text = payloadText;
                }
            }
            text.ifPresent(value -> assistant.append(value));
            return;
        }
        assistant.append(chunk.getData());
    }

    private Optional<Object> firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
