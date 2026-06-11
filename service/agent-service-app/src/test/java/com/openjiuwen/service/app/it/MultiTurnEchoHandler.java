package com.openjiuwen.service.app.it;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub {@link AgentHandler} for integration tests (no Runner/LLM).
 */
class MultiTurnEchoHandler implements AgentHandler {

    private final Map<String, List<String>> history = new ConcurrentHashMap<>();

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            String reply = buildReply(request);
            observer.onNext(new QueryChunk("chunk", result(request, reply)));
            observer.onComplete();
        } catch (Exception ex) {
            observer.onNext(new QueryChunk("error", Map.of("type", "error", "error", ex.getMessage())));
            observer.onError(ex);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String reply = buildReply(request);
        return new QueryResponse(result(request, reply), request.getConversationId());
    }

    @Override
    public void clearSession(String conversationId) {
        if (conversationId != null) {
            history.remove(conversationId);
        }
    }

    private String buildReply(ServeRequest request) {
        String cid = request.getConversationId();
        String query = request.lastUserQuery();
        List<String> prior = history.computeIfAbsent(cid, k -> new ArrayList<>());
        String reply = "turn" + (prior.size() + 1) + ":" + query;
        if (!prior.isEmpty()) {
            reply += "|prev=" + String.join(",", prior);
        }
        prior.add(query);
        return reply;
    }

    private static Map<String, Object> result(ServeRequest request, String reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", reply);
        result.put("conversation_id", request.getConversationId());
        result.put("user_id", request.getUserId());
        result.put("space_id", request.getSpaceId());
        if (request.getTenantId() != null) {
            result.put("tenant_id", request.getTenantId());
        }
        result.put("messages_size", request.getMessages().size());
        return result;
    }
}
