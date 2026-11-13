/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;

import java.util.Map;
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
     * Resumes a user-paused session from the persisted paused boundary
     * (issue #117): the implementation locates the PAUSED step cursor of the
     * session, flips it back to RUNNING, and continues the execution from
     * that boundary without replaying completed rounds. The default
     * implementation is a no-op for backward compatibility; handlers with
     * resume support override this method and
     * {@link #supportsSessionOperations()}.
     *
     * @param sessionId session id of the paused session
     * @since 0.1.5
     */
    default void resumeSession(String sessionId) {
    }

    /**
     * Publishes a user pause intent for a running session (issue #117): the
     * executing agent loop observes the intent at its next step boundary,
     * persists the PAUSED step cursor there, and stops without replaying
     * completed rounds. The default implementation is a no-op for backward
     * compatibility; handlers with pause support override this method and
     * {@link #supportsSessionOperations()}, so callers probe the capability
     * first and never publish an intent that would be silently dropped -
     * an interrupted execution without a persisted pause point is
     * unresumable.
     *
     * @param sessionId session id of the running session
     * @param pauseInfo pause metadata persisted with the paused cursor
     *                  (operator, reason, timestamps), may be null
     * @since 0.1.5
     */
    default void requestPause(String sessionId, Map<String, Object> pauseInfo) {
    }

    /**
     * Compacts the persisted context of an idle session (issue #117): the
     * implementation restores the session state from the checkpoint, runs the
     * context compression processors, and persists the compacted state back
     * so the next invocation continues from the compacted context. The
     * default implementation is a no-op returning an empty map for backward
     * compatibility; handlers with compaction support override this method
     * and {@link #supportsSessionOperations()}.
     *
     * @param sessionId session id of the idle session
     * @param options compression options (processor types, thresholds); may
     *                be null for the default strategy
     * @return a result map with the compression outcome (e.g. {@code result}
     *         and token footprint); empty when the operation is not supported
     * @since 0.1.5
     */
    default Map<String, Object> compactSession(String sessionId, Map<String, Object> options) {
        return Map.of();
    }

    /**
     * Whether this handler implements the session operations introduced by
     * issue #117 ({@link #resumeSession(String)},
     * {@link #requestPause(String, Map)},
     * {@link #compactSession(String, Map)}). Implementations overriding any
     * of those methods must also override this probe to return {@code true};
     * the default {@code false} lets callers reject the operations up front
     * (e.g. HTTP 501) instead of silently dropping a pause intent that would
     * leave an interrupted session unresumable.
     *
     * @return {@code true} when the session operations are implemented
     * @since 0.1.5
     */
    default boolean supportsSessionOperations() {
        return false;
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
