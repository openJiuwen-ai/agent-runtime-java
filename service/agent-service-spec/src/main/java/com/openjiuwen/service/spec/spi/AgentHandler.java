/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;

import java.util.Optional;

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
     * @throws AgentExecutionException when an adapter reports a structured execution failure
     */
    QueryResponse query(ServeRequest request);

    /**
     * Executes a streaming query.
     *
     * @param request the serve request
     * @param observer the stream observer
     * @see AgentExecutionException structured failure type passed to
     *      {@link QueryStreamObserver#onError(Throwable)}
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

    /**
     * Called once before the handler starts processing a request.
     * Implementations may use this to acquire resources scoped to that
     * processing (e.g. a dedicated agent instance) and release them via
     * {@link #completeTask(Optional)}.
     *
     * <p>Processing one request may involve one or several
     * {@code query}/{@code streamQuery} invocations (an orchestrator may
     * re-drive the request before it completes), so the acquired resources
     * must remain usable across all of them.
     *
     * @param request the serve request about to be processed
     * @return an {@link Optional} wrapping an opaque token that MUST be
     *         passed back to {@link #completeTask(Optional)} to release the
     *         acquired resources; {@link Optional#empty()} when no resources
     *         were acquired
     * @since 0.1.2
     */
    default Optional<Object> prepareTask(ServeRequest request) {
        return Optional.empty();
    }

    /**
     * Called once after request processing ends (in a finally block,
     * regardless of success or failure). Implementations may use this to
     * release the resources acquired in {@link #prepareTask(ServeRequest)}.
     *
     * <p>Implementations MUST treat {@link Optional#empty()} or foreign tokens
     * as a no-op: an empty token means {@link #prepareTask(ServeRequest)} never
     * acquired resources for this processing (e.g. it rejected the request
     * because the conversation was busy), and the caller's finally must not
     * disturb resources owned by another in-flight request.
     *
     * @param taskToken the token returned by {@link #prepareTask(ServeRequest)}
     *                  for this processing, or {@link Optional#empty()} when
     *                  nothing was acquired
     * @since 0.1.2
     */
    default void completeTask(Optional<Object> taskToken) {
    }
}
