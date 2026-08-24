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
     * Called once before the orchestrator's execution loop begins for a task.
     * Implementations may use this to acquire a task-level agent that will be
     * reused across all loop iterations (e.g. remote-tool roundtrips).
     *
     * @param request the initial serve request for this task
     * @return an {@link Optional} wrapping an opaque task token that MUST be
     *         passed back to {@link #completeTask(Optional)} to release the
     *         acquired resources; {@link Optional#empty()} when no task-level
     *         resources were acquired
     * @since 0.1.2
     */
    default Optional<Object> prepareTask(ServeRequest request) {
        return Optional.empty();
    }

    /**
     * Called once after the orchestrator's execution loop ends for a task
     * (in a finally block, regardless of success or failure).
     * Implementations may use this to release a task-level agent.
     *
     * <p>Implementations MUST treat {@link Optional#empty()} or foreign tokens
     * as a no-op: an empty token means {@link #prepareTask(ServeRequest)} never
     * acquired resources for this task (e.g. it rejected the task because the
     * conversation was busy), and the caller's finally must not disturb
     * resources owned by another in-flight task.
     *
     * @param taskToken the token returned by {@link #prepareTask(ServeRequest)}
     *                  for this task, or {@link Optional#empty()} when nothing
     *                  was acquired
     * @since 0.1.2
     */
    default void completeTask(Optional<Object> taskToken) {
    }
}
