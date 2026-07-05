/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

/**
 * Placeholder {@link AgentHandler} when no custom bean and no {@code agent-id}
 * is configured.
 *
 * @since 0.1.0
 */
public final class AgentHandlerHolder implements AgentHandler {
    private volatile AgentHandler delegate;

    /**
     * Sets the delegate handler after init phase completes.
     *
     * @param handler the loaded agent handler
     */
    public void setHandler(AgentHandler handler) {
        this.delegate = handler;
    }

    /**
     * Returns whether a delegate handler has been loaded.
     *
     * @return {@code true} when a delegate handler is present
     */
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

    @Override
    public void start() {
        AgentHandler handler = delegate;
        if (handler != null) {
            handler.start();
        }
    }

    @Override
    public void stop() {
        AgentHandler handler = delegate;
        if (handler != null) {
            handler.stop();
        }
    }

    @Override
    public void clearSession(String conversationId) {
        AgentHandler handler = delegate;
        if (handler != null) {
            handler.clearSession(conversationId);
        }
    }

    private AgentHandler requireHandler() {
        AgentHandler handler = delegate;
        if (handler == null) {
            throw new IllegalStateException("Agent not loaded; init phase has not completed");
        }
        return handler;
    }
}
