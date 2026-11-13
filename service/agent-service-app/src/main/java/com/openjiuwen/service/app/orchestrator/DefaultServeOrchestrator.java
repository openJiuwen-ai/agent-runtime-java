/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Default orchestration: delegates to {@link AgentHandler} with unified error
 * surfacing. Drives the {@link AgentHandler#prepareTask} /
 * {@link AgentHandler#completeTask} lifecycle hooks around each delegation,
 * so handlers relying on request-scoped resources behave correctly under any
 * {@link ServeOrchestrator} implementation.
 *
 * <p>Reference implementation only: production wiring currently uses
 * {@code A2AEnabledServeOrchestrator}, which adds remote-invocation
 * continuation on top of the same hook protocol.
 *
 * @since 2026-07-03
 */
public class DefaultServeOrchestrator implements ServeOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(DefaultServeOrchestrator.class);

    private final AgentHandler agentHandler;

    private final ActiveStreamRegistry streamRegistry;

    public DefaultServeOrchestrator(AgentHandler agentHandler, ActiveStreamRegistry streamRegistry) {
        this.agentHandler = agentHandler;
        this.streamRegistry = streamRegistry;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String conversationId = request.getConversationId();
        Optional<Object> taskToken = Optional.empty();
        try {
            taskToken = agentHandler.prepareTask(request);
            return agentHandler.query(request);
        } catch (Exception ex) {
            log.error("Query failed for conversation_id={}", conversationId, ex);
            throw ex;
        } finally {
            // Empty token means prepareTask acquired nothing (e.g. busy
            // rejection) — completeTask must not disturb the other task.
            agentHandler.completeTask(taskToken);
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String conversationId = request.getConversationId() != null ? request.getConversationId() : "";
        StreamCancellationHandle handle = streamRegistry.register(conversationId);
        Optional<Object> taskToken = Optional.empty();
        try {
            taskToken = agentHandler.prepareTask(request);
            agentHandler.streamQuery(request, wrapObserver(observer, handle));
        } catch (CancellationException ex) {
            log.debug("Stream query cancelled for conversation_id={}", conversationId);
            observer.onComplete();
        } catch (Exception ex) {
            log.error("Stream query failed for conversation_id={}", conversationId, ex);
            Map<String, Object> errorEvent = new LinkedHashMap<>();
            errorEvent.put("type", "error");
            errorEvent.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
            observer.onNext(new QueryChunk("error", errorEvent));
            observer.onError(ex);
        } finally {
            // Empty token means prepareTask acquired nothing (e.g. busy
            // rejection) — completeTask must not disturb the other task.
            try {
                agentHandler.completeTask(taskToken);
            } finally {
                streamRegistry.unregister(conversationId, handle);
            }
        }
    }

    @Override
    public void cancelActive(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        log.debug("Cancelling active stream for conversation_id={}", conversationId);
        streamRegistry.cancel(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        log.info("Resetting conversation for conversation_id={}", conversationId);
        cancelActive(conversationId);
        agentHandler.clearSession(conversationId);
    }

    /**
     * The capability probe follows the delegate handler: this orchestrator
     * forwards the issue #117 session operations to the handler, so it
     * supports them exactly when the handler does.
     *
     * @return {@code true} when the delegate handler implements the session
     *         operations
     */
    @Override
    public boolean supportsSessionOperations() {
        return agentHandler.supportsSessionOperations();
    }

    /**
     * Resumes a user-paused session by delegating to the handler's explicit
     * resume entry (issue #117): the handler flips the persisted PAUSED step
     * cursor back to RUNNING and continues the execution from that boundary.
     * The delegation drives the prepareTask/completeTask hooks like every
     * other request-scoped delegation, so handlers relying on those
     * resources behave identically on the resume path.
     *
     * @param sessionId session id of the paused session
     */
    @Override
    public void resumeSession(String sessionId) {
        requireSessionOperations("resume");
        log.info("Resuming paused session for session_id={}", sessionId);
        ServeRequest request = sessionOperationRequest(sessionId);
        Optional<Object> taskToken = Optional.empty();
        try {
            taskToken = agentHandler.prepareTask(request);
            agentHandler.resumeSession(sessionId);
        } finally {
            agentHandler.completeTask(taskToken);
        }
    }

    /**
     * Publishes the user pause intent through the handler (issue #117): the
     * executing agent loop stops at its next step boundary with the PAUSED
     * cursor persisted. The delegation drives the prepareTask/completeTask
     * hooks so the intent is published under the same request-scoped
     * resources a regular query delegation would hold.
     *
     * @param sessionId session id of the running session
     * @param pauseInfo pause metadata persisted with the paused cursor
     */
    @Override
    public void requestPause(String sessionId, Map<String, Object> pauseInfo) {
        requireSessionOperations("pause");
        log.info("Requesting pause for session_id={}", sessionId);
        ServeRequest request = sessionOperationRequest(sessionId);
        Optional<Object> taskToken = Optional.empty();
        try {
            taskToken = agentHandler.prepareTask(request);
            agentHandler.requestPause(sessionId, pauseInfo);
        } finally {
            agentHandler.completeTask(taskToken);
        }
    }

    /**
     * Compacts the persisted context of an idle session by delegating to the
     * handler (issue #117): the handler restores the session from the
     * checkpoint, runs the compression processors, and persists the compacted
     * state. The delegation drives the prepareTask/completeTask hooks like
     * every other request-scoped delegation.
     *
     * @param sessionId session id of the idle session
     * @param options compression options (processor types, thresholds); may
     *                be null for the default strategy
     * @return a result map with the compression outcome
     */
    @Override
    public Map<String, Object> compactSession(String sessionId, Map<String, Object> options) {
        requireSessionOperations("compact");
        log.info("Compacting session for session_id={}", sessionId);
        ServeRequest request = sessionOperationRequest(sessionId);
        Optional<Object> taskToken = Optional.empty();
        try {
            taskToken = agentHandler.prepareTask(request);
            return agentHandler.compactSession(sessionId, options);
        } finally {
            agentHandler.completeTask(taskToken);
        }
    }

    /**
     * Builds the minimal serve request identifying a session-operations
     * delegation: the prepareTask hook keys its request-scoped resources on
     * the conversation id, mirroring what a regular query delegation for the
     * same session would carry.
     *
     * @param sessionId target session id
     * @return the serve request for the delegation
     */
    private static ServeRequest sessionOperationRequest(String sessionId) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(sessionId);
        return request;
    }

    /**
     * Guards the session-operations delegations against handlers without
     * pause/resume/compact support: delegating anyway would make a pause a
     * silent no-op followed by an interrupt that leaves the session without
     * a persisted pause point - unresumable.
     *
     * @param operation operation name for the error message
     */
    private void requireSessionOperations(String operation) {
        if (!agentHandler.supportsSessionOperations()) {
            throw new IllegalStateException("Session operation '" + operation
                    + "' is not supported by the configured agent handler");
        }
    }

    private QueryStreamObserver wrapObserver(QueryStreamObserver observer, StreamCancellationHandle handle) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (isCancelled()) {
                    return;
                }
                observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                if (!isCancelled()) {
                    observer.onError(error);
                } else {
                    observer.onComplete();
                }
            }

            @Override
            public void onComplete() {
                observer.onComplete();
            }

            @Override
            public void onComplete(QueryResponse response) {
                if (!isCancelled()) {
                    observer.onComplete(response);
                } else {
                    observer.onComplete();
                }
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled() || observer.isCancelled();
            }
        };
    }
}
