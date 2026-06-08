package com.openjiuwen.service.adapters.agentfw;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Map;

/**
 * Default {@link AgentHandler} delegating to AgentCore {@code Runner} (placeholder until Runner wiring).
 */
public class CoreAgentHandler implements AgentHandler {

    @Override
    public QueryResponse query(ServeRequest request) {
        return new QueryResponse(
                Map.of("type", "placeholder", "message", "CoreAgentHandler not yet wired to Runner"),
                request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            observer.onNext(new QueryChunk("chunk", Map.of(
                    "type", "placeholder",
                    "message", "CoreAgentHandler not yet wired to Runner")));
            observer.onComplete();
        } catch (Exception ex) {
            observer.onError(ex);
        }
    }
}
