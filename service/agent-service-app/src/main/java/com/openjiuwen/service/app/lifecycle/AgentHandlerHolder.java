package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

/**
 * Lazy {@link AgentHandler} populated during the init phase ({@code AgentInitHook}),
 * aligned with Python {@code app.agent = ...} in {@code @app.init}.
 */
public final class AgentHandlerHolder implements AgentHandler {

    private volatile AgentHandler delegate;

    public void setHandler(AgentHandler handler) {
        this.delegate = handler;
    }

    public boolean isLoaded() {
        return delegate != null;
    }

    public AgentHandler getDelegate() {
        return delegate;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        return requireHandler().query(request);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        requireHandler().streamQuery(request, observer);
    }

    private AgentHandler requireHandler() {
        AgentHandler handler = delegate;
        if (handler == null) {
            throw new IllegalStateException("Agent not loaded; init phase has not completed");
        }
        return handler;
    }
}
