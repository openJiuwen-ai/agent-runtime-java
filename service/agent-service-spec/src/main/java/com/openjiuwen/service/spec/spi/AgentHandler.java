/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Agent framework adapter SPI (AgentFWAdapters).
 *
 * @since 0.1.0
 */
public interface AgentHandler {
    /**
     * Executes a non-streaming query.
     *
     * @param request the serve request
     * @return the aggregated query response
     */
    QueryResponse query(ServeRequest request);

    /**
     * Executes a streaming query.
     *
     * @param request the serve request
     * @param observer the stream observer
     */
    void streamQuery(ServeRequest request, QueryStreamObserver observer);

    /**
     * Start the handler before serving (e.g. AgentCore {@code Runner.start()}).
     * Invoked once during service init after the handler is loaded and init hooks
     * have run.
     */
    default void start() {
    }

    /**
     * Stop the handler during service shutdown.
     */
    default void stop() {
    }

    /**
     * Clear persisted session state for a conversation (e.g.
     * {@code Runner.release(sessionId)}).
     *
     * @param conversationId conversationId
     */
    default void clearSession(String conversationId) {
    }
}
