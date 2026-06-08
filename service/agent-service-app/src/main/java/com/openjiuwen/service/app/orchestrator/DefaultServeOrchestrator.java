package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

/**
 * Default orchestration: session mapping hooks and delegation to {@link AgentHandler}.
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
        agentHandler.streamQuery(request, observer);
    }
}
