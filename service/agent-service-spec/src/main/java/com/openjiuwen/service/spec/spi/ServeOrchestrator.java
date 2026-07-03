/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Ingress protocol-neutral orchestration kernel (AgentApp).
 *
 * @since 0.1.0
 */
public interface ServeOrchestrator {

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
     * Cancel active streaming (or in-flight) execution for the given conversation.
     */
    void cancelActive(String conversationId);

    /**
     * Reset conversation context: cancel active streams then clear session state.
     */
    void resetConversation(String conversationId);
}
