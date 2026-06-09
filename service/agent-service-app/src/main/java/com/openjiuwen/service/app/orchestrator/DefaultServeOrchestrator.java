package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Default orchestration: delegates to {@link AgentHandler} with unified error surfacing.
 */
public class DefaultServeOrchestrator implements ServeOrchestrator {

    private final AgentHandler agentHandler;

    public DefaultServeOrchestrator(AgentHandler agentHandler) {
        this.agentHandler = agentHandler;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        return agentHandler.query(request);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            agentHandler.streamQuery(request, observer);
        } catch (CancellationException ex) {
            observer.onComplete();
        } catch (Exception ex) {
            Map<String, Object> errorEvent = new LinkedHashMap<>();
            errorEvent.put("type", "error");
            errorEvent.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
            observer.onNext(new QueryChunk("error", errorEvent));
            observer.onError(ex);
        }
    }
}
