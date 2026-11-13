/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import com.openjiuwen.service.app.config.SessionApiProperties;
import com.openjiuwen.service.app.controller.query.QueryIngressSupport;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared orchestration behind the session management endpoints, decoupled
 * from the servlet and reactive stacks (issue #117).
 *
 * <p>The service resolves the session state machine verdicts (pause/resume/
 * terminate) against the active-task registry and delegates the accepted
 * transitions to {@link SessionPauseResumeCoordinator}; the web-layer
 * controllers only translate the verdict into stack-specific responses.</p>
 *
 * @since 0.1.5
 */
public class SessionManageService {
    private static final Logger log = LoggerFactory.getLogger(SessionManageService.class);

    /** Shared detail text of the 503 bodies returned when the task-store query fails. */
    private static final String STATE_UNAVAILABLE_DETAIL =
            "the session state could not be determined; retry after the task store recovers";

    private final SessionPauseResumeCoordinator pauseResumeCoordinator;
    private final ObjectProvider<ActiveTaskQuery> activeTaskQueryProvider;
    private final ObjectProvider<AgentReadiness> readinessProvider;
    private final SessionApiProperties properties;

    /**
     * Creates the shared session management service.
     *
     * @param pauseResumeCoordinator pause/resume orchestration
     * @param activeTaskQueryProvider active task query provider, may be absent
     * @param readinessProvider agent readiness provider, may be absent
     * @param properties session API configuration
     */
    public SessionManageService(SessionPauseResumeCoordinator pauseResumeCoordinator,
            ObjectProvider<ActiveTaskQuery> activeTaskQueryProvider, ObjectProvider<AgentReadiness> readinessProvider,
            SessionApiProperties properties) {
        this.pauseResumeCoordinator = Objects.requireNonNull(pauseResumeCoordinator, "pauseResumeCoordinator");
        this.activeTaskQueryProvider = Objects.requireNonNull(activeTaskQueryProvider,
                "activeTaskQueryProvider");
        this.readinessProvider = Objects.requireNonNull(readinessProvider, "readinessProvider");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Resolves the lifecycle state of one session: the coordinator's recorded
     * PAUSED/TERMINATED transitions win, then RUNNING when an active task
     * exists, IDLE otherwise. A blank id yields null (treated as unknown).
     *
     * @param sessionId target session id
     * @return the resolved state, or null when the session is unknown
     */
    public SessionState sessionState(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        SessionState managed = pauseResumeCoordinator.managedState(sessionId);
        if (managed != null) {
            return managed;
        }
        ActiveTaskQuery query = activeTaskQueryProvider.getIfAvailable();
        if (query != null) {
            try {
                return hasActiveTask(query, sessionId) ? SessionState.RUNNING : SessionState.IDLE;
            } catch (SessionStateException exception) {
                return SessionState.UNKNOWN;
            }
        }
        return SessionState.IDLE;
    }

    /**
     * Validates readiness for session management operations.
     *
     * @return error body when the agent is not ready, empty map otherwise
     */
    public Map<String, Object> readinessError() {
        AgentReadiness readiness = readinessProvider.getIfAvailable();
        if (readiness != null && !readiness.isAgentLoaded()) {
            return QueryIngressSupport.agentNotReady();
        }
        return Map.of();
    }

    /**
     * Orchestrates a pause request against the state machine: terminated and
     * idle sessions are rejected with 409, already-paused sessions collapse
     * into the idempotent result, running sessions are accepted for the
     * asynchronous suspension.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @param reason pause reason for audit
     * @return the orchestration outcome for response mapping
     */
    public PauseOutcome pause(String sessionId, String operator, String reason) {
        return pause(sessionId, operator, null, reason);
    }

    /**
     * Orchestrates a pause request with the caller's tenant identity: the
     * tenant travels with the pause intent so the intent registry key
     * resolves to the same tenant-scoped key the executing loop consumes
     * under tenant-aware deployments.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @param tenantId tenant identity of the calling tenant, may be null in
     *        tenant-less deployments
     * @param reason pause reason for audit
     * @return the orchestration outcome for response mapping
     */
    public PauseOutcome pause(String sessionId, String operator, String tenantId, String reason) {
        if (!properties.isOperationsEnabled()) {
            return PauseOutcome.disabled();
        }
        if (!pauseResumeCoordinator.supportsSessionOperations()) {
            return PauseOutcome.unsupported();
        }
        SessionState current = sessionState(sessionId);
        if (current == null) {
            return PauseOutcome.notFound();
        }
        if (current == SessionState.UNKNOWN) {
            return PauseOutcome.unavailable();
        }
        if (current == SessionState.PAUSED) {
            return PauseOutcome.idempotent();
        }
        if (current == SessionState.TERMINATED || current == SessionState.IDLE) {
            return PauseOutcome.conflict(current);
        }
        SessionOperationResult result = pauseResumeCoordinator.pause(sessionId, operator, tenantId, reason);
        logAudit("pause", sessionId, operator, result.detail());
        return PauseOutcome.accepted(result);
    }

    /**
     * Orchestrates a resume request against the state machine: running and
     * terminated sessions are rejected with 409, a rejected admission keeps
     * the session paused with 429, and an accepted resume continues from the
     * persisted paused boundary.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @return the orchestration outcome for response mapping
     */
    public ResumeOutcome resume(String sessionId, String operator) {
        if (!properties.isOperationsEnabled()) {
            return ResumeOutcome.disabled();
        }
        if (!pauseResumeCoordinator.supportsSessionOperations()) {
            return ResumeOutcome.unsupported();
        }
        SessionState current = sessionState(sessionId);
        if (current == null) {
            return ResumeOutcome.notFound();
        }
        if (current == SessionState.UNKNOWN) {
            return ResumeOutcome.unavailable();
        }
        if (current == SessionState.RUNNING) {
            return ResumeOutcome.conflict(SessionState.RUNNING);
        }
        if (current == SessionState.TERMINATED) {
            return ResumeOutcome.conflict(SessionState.TERMINATED);
        }
        SessionOperationResult result = pauseResumeCoordinator.resume(sessionId, operator);
        if (!result.isAccepted()) {
            return ResumeOutcome.admissionRejected(result.detail());
        }
        logAudit("resume", sessionId, operator, result.detail() + " (source=explicit-api)");
        return ResumeOutcome.accepted(result);
    }

    /**
     * Orchestrates a terminate request: the shared interrupt chain cancels the
     * active execution and the session state becomes TERMINATED. Repeated
     * terminate requests are idempotent.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @return the orchestration outcome for response mapping
     */
    public TerminateOutcome terminate(String sessionId, String operator) {
        SessionState current = sessionState(sessionId);
        if (current == null) {
            return TerminateOutcome.notFound();
        }
        if (current == SessionState.TERMINATED) {
            return TerminateOutcome.idempotent();
        }
        // An undecidable state does not block a terminate: interrupting an
        // idle session is a no-op, and skipping the interrupt because the
        // state query failed would leave a possibly-running session alive.
        SessionOperationResult result = pauseResumeCoordinator.terminate(sessionId, operator);
        if (result == null) {
            return TerminateOutcome.accepted(SessionOperationResult.accepted(SessionState.TERMINATED,
                    "terminate accepted; active execution will be interrupted"));
        }
        logAudit("terminate", sessionId, operator, result.detail());
        return TerminateOutcome.accepted(result);
    }

    /**
     * Orchestrates a compact request against the state machine: only idle
     * sessions are accepted because compaction must not race an in-flight
     * execution or a suspended pause state; running, paused, and terminated
     * sessions are rejected with 409. The compaction runs synchronously on
     * the caller thread and its outcome is audited.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @param options compression options (processor types, thresholds); may
     *                be null for the default strategy
     * @return the orchestration outcome for response mapping
     */
    public CompactOutcome compact(String sessionId, String operator, Map<String, Object> options) {
        if (!properties.isOperationsEnabled()) {
            return CompactOutcome.disabled();
        }
        if (!pauseResumeCoordinator.supportsSessionOperations()) {
            return CompactOutcome.unsupported();
        }
        SessionState current = sessionState(sessionId);
        if (current == null) {
            return CompactOutcome.notFound();
        }
        if (current == SessionState.UNKNOWN) {
            return CompactOutcome.unavailable();
        }
        if (current != SessionState.IDLE) {
            return CompactOutcome.conflict(current);
        }
        try {
            Map<String, Object> result = pauseResumeCoordinator.compactSession(sessionId, operator, options);
            logAudit("compact", sessionId, operator, String.valueOf(result));
            return CompactOutcome.accepted(result);
        } catch (RuntimeException exception) {
            log.warn("Compact failed for session_id={}: {}", sessionId, exception.getMessage());
            return CompactOutcome.failed(exception.getMessage());
        }
    }

    /**
     * Builds the detail body of one session for the GET endpoints.
     *
     * @param sessionId target session id
     * @return detail body, or empty map when the session does not exist
     */
    public Map<String, Object> sessionDetail(String sessionId) {
        SessionState state = sessionState(sessionId);
        if (state == null) {
            return Map.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("state", state.name());
        return body;
    }

    /**
     * Whether the session holds an active task; the query result is
     * authoritative for the RUNNING vs IDLE verdict. A query failure throws
     * so callers fail closed instead of misjudging an undecidable session as
     * idle: a compact accepted on a running session would race its
     * checkpoint writes, a pause rejected on a truly running session leaves
     * it unmanagable, and a resume allowed onto a running session corrupts
     * the continuation state.
     *
     * @param query active task query port
     * @param sessionId target session id
     * @return true when the session has an active task
     * @throws SessionStateException when the task-store query fails
     */
    private boolean hasActiveTask(ActiveTaskQuery query, String sessionId) {
        try {
            return query.snapshot().getTasks().stream()
                    .anyMatch(task -> sessionId.equals(task.getConversationId()));
        } catch (RuntimeException exception) {
            log.warn("Active task lookup failed for session_id={}: {}", sessionId, exception.getMessage());
            throw new SessionStateException("Session state is unavailable for session_id " + sessionId
                    + ": the active task query failed", exception);
        }
    }

    private void logAudit(String action, String sessionId, String operator, String detail) {
        log.info("Session audit: action={}, session_id={}, operator={}, detail={}", action, sessionId, operator,
                detail);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Signals an undecidable session state so the web layer maps it to 503
     * instead of guessing a verdict on a failed task-store query.
     *
     * @since 0.1.5
     */
    public static class SessionStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the failure with its cause.
         *
         * @param message failure description
         * @param cause the underlying task-store failure
         */
        public SessionStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Verdict of a pause orchestration, mapped by the web layer. */
    public record PauseOutcome(HttpStatus status, boolean isAlreadyInState, Map<String, Object> body) {

        static PauseOutcome disabled() {
            return new PauseOutcome(HttpStatus.NOT_FOUND, false,
                    Map.of("error", "pause endpoints are disabled"));
        }

        static PauseOutcome notFound() {
            return new PauseOutcome(HttpStatus.NOT_FOUND, false,
                    Map.of("error", "session not found"));
        }

        static PauseOutcome unsupported() {
            return new PauseOutcome(HttpStatus.NOT_IMPLEMENTED, false,
                    Map.of("error", "SESSION_OPERATION_UNSUPPORTED",
                            "detail", "pause is not supported by the configured orchestrator"));
        }

        static PauseOutcome unavailable() {
            return new PauseOutcome(HttpStatus.SERVICE_UNAVAILABLE, false,
                    Map.of("error", "SESSION_STATE_UNAVAILABLE",
                            "detail", STATE_UNAVAILABLE_DETAIL));
        }

        static PauseOutcome idempotent() {
            return new PauseOutcome(HttpStatus.OK, true,
                    Map.of("accepted", true, "state", SessionState.PAUSED.name(), "alreadyPaused", true));
        }

        static PauseOutcome conflict(SessionState current) {
            return new PauseOutcome(HttpStatus.CONFLICT, false,
                    Map.of("error", "INVALID_SESSION_STATE", "state", current.name()));
        }

        static PauseOutcome accepted(SessionOperationResult result) {
            return new PauseOutcome(HttpStatus.ACCEPTED, false, acceptedBody(result));
        }
    }

    /** Verdict of a resume orchestration, mapped by the web layer. */
    public record ResumeOutcome(HttpStatus status, Map<String, Object> body) {

        static ResumeOutcome disabled() {
            return new ResumeOutcome(HttpStatus.NOT_FOUND,
                    Map.of("error", "resume endpoints are disabled"));
        }

        static ResumeOutcome notFound() {
            return new ResumeOutcome(HttpStatus.NOT_FOUND,
                    Map.of("error", "session not found"));
        }

        static ResumeOutcome unsupported() {
            return new ResumeOutcome(HttpStatus.NOT_IMPLEMENTED,
                    Map.of("error", "SESSION_OPERATION_UNSUPPORTED",
                            "detail", "resume is not supported by the configured orchestrator"));
        }

        static ResumeOutcome unavailable() {
            return new ResumeOutcome(HttpStatus.SERVICE_UNAVAILABLE,
                    Map.of("error", "SESSION_STATE_UNAVAILABLE",
                            "detail", STATE_UNAVAILABLE_DETAIL));
        }

        static ResumeOutcome conflict(SessionState current) {
            return new ResumeOutcome(HttpStatus.CONFLICT,
                    Map.of("error", "INVALID_SESSION_STATE", "state", current.name()));
        }

        static ResumeOutcome admissionRejected(String detail) {
            return new ResumeOutcome(HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("error", "ADMISSION_REJECTED", "detail", String.valueOf(detail)));
        }

        static ResumeOutcome accepted(SessionOperationResult result) {
            return new ResumeOutcome(HttpStatus.ACCEPTED, acceptedBody(result));
        }
    }

    /** Verdict of a terminate orchestration, mapped by the web layer. */
    public record TerminateOutcome(HttpStatus status, boolean isAlreadyInState, Map<String, Object> body) {

        static TerminateOutcome notFound() {
            return new TerminateOutcome(HttpStatus.NOT_FOUND, false,
                    Map.of("error", "session not found"));
        }

        static TerminateOutcome idempotent() {
            return new TerminateOutcome(HttpStatus.OK, true,
                    Map.of("accepted", true, "state", SessionState.TERMINATED.name(), "alreadyTerminated", true));
        }

        static TerminateOutcome accepted(SessionOperationResult result) {
            return new TerminateOutcome(HttpStatus.ACCEPTED, false, acceptedBody(result));
        }
    }

    /** Verdict of a compact orchestration, mapped by the web layer. */
    public record CompactOutcome(HttpStatus status, Map<String, Object> body) {

        static CompactOutcome disabled() {
            return new CompactOutcome(HttpStatus.NOT_FOUND,
                    Map.of("error", "compact endpoints are disabled"));
        }

        static CompactOutcome notFound() {
            return new CompactOutcome(HttpStatus.NOT_FOUND,
                    Map.of("error", "session not found"));
        }

        static CompactOutcome unsupported() {
            return new CompactOutcome(HttpStatus.NOT_IMPLEMENTED,
                    Map.of("error", "SESSION_OPERATION_UNSUPPORTED",
                            "detail", "compact is not supported by the configured orchestrator"));
        }

        static CompactOutcome unavailable() {
            return new CompactOutcome(HttpStatus.SERVICE_UNAVAILABLE,
                    Map.of("error", "SESSION_STATE_UNAVAILABLE",
                            "detail", STATE_UNAVAILABLE_DETAIL));
        }

        static CompactOutcome conflict(SessionState current) {
            return new CompactOutcome(HttpStatus.CONFLICT,
                    Map.of("error", "INVALID_SESSION_STATE", "state", current.name()));
        }

        static CompactOutcome accepted(Map<String, Object> result) {
            Map<String, Object> body = new LinkedHashMap<>(result);
            body.put("accepted", true);
            return new CompactOutcome(HttpStatus.OK, body);
        }

        static CompactOutcome failed(String detail) {
            return new CompactOutcome(HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "COMPACT_FAILED", "detail", String.valueOf(detail)));
        }
    }

    private static Map<String, Object> acceptedBody(SessionOperationResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", result.isAccepted());
        body.put("state", result.state() == null ? null : result.state().name());
        if (!result.detail().isBlank()) {
            body.put("detail", result.detail());
        }
        return body;
    }
}
