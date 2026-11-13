/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Map;

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
     *
     * @param conversationId conversationId
     */
    void cancelActive(String conversationId);

    /**
     * Reset conversation context: cancel active streams then clear session state.
     *
     * @param conversationId conversationId
     */
    void resetConversation(String conversationId);

    /**
     * Resumes a user-paused session from the persisted paused boundary
     * (issue #117): the implementation continues the execution from the
     * PAUSED step cursor without replaying completed rounds. The default
     * implementation is a no-op for backward compatibility; orchestrators
     * with resume support override this method and
     * {@link #supportsSessionOperations()}.
     *
     * @param sessionId session id of the paused session
     * @since 0.1.5
     */
    default void resumeSession(String sessionId) {
    }

    /**
     * Publishes a user pause intent for a running session (issue #117): the
     * orchestrator forwards the intent to the handler so the executing agent
     * loop stops at its next step boundary with the PAUSED cursor persisted.
     * The default implementation is a no-op for backward compatibility;
     * orchestrators with pause support override this method and
     * {@link #supportsSessionOperations()}, so callers probe the capability
     * first and never interrupt an execution whose pause intent would be
     * silently dropped - an interrupted execution without a persisted pause
     * point is unresumable.
     *
     * @param sessionId session id of the running session
     * @param pauseInfo pause metadata persisted with the paused cursor,
     *                  may be null
     * @since 0.1.5
     */
    default void requestPause(String sessionId, Map<String, Object> pauseInfo) {
    }

    /**
     * Compacts the persisted context of an idle session (issue #117): the
     * orchestrator forwards the compaction to the handler, which restores the
     * session from the checkpoint, runs the compression processors, and
     * persists the compacted state. The default implementation is a no-op
     * returning an empty map for backward compatibility; orchestrators with
     * compaction support override this method and
     * {@link #supportsSessionOperations()}.
     *
     * @param sessionId session id of the idle session
     * @param options compression options (processor types, thresholds); may
     *                be null for the default strategy
     * @return a result map with the compression outcome; empty when the
     *         operation is not supported
     * @since 0.1.5
     */
    default Map<String, Object> compactSession(String sessionId, Map<String, Object> options) {
        return Map.of();
    }

    /**
     * Whether this orchestrator implements the session operations introduced
     * by issue #117 ({@link #resumeSession(String)},
     * {@link #requestPause(String, Map)},
     * {@link #compactSession(String, Map)}). Implementations overriding any
     * of those methods must also override this probe to return {@code true};
     * the default {@code false} lets callers reject the operations up front
     * (e.g. HTTP 501) instead of interrupting an execution whose pause
     * intent would be silently dropped and lost.
     *
     * @return {@code true} when the session operations are implemented
     * @since 0.1.5
     */
    default boolean supportsSessionOperations() {
        return false;
    }
}
