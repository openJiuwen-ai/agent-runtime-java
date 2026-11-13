/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;

/**
 * Orchestrates user-requested pause and resume transitions of managed sessions
 * (issue #117 section 3.2a).
 *
 * <p>Pause shares the terminate interrupt chain ({@link ActiveStreamInterruptor}
 * plus {@link AgentInterruptHandler} callbacks) but records a resumable intent:
 * the runtime flips the session to PAUSED and the core step-checkpoint layer
 * persists the PAUSED cursor, from which only the explicit resume may
 * continue. Resume is treated as a fresh task admission: it must pass the
 * {@link TaskAdmissionGate}, and a rejected admission keeps the session paused
 * (429, retryable).</p>
 *
 * <p>Both operations are accepted asynchronously (HTTP 202 semantics): the
 * coordinator publishes the transition decision synchronously and performs the
 * interrupt/admission work on the supplied executor. Repeated pause requests
 * within the debounce window are merged idempotently; resume on a
 * running/terminated session is rejected with 409.</p>
 *
 * @since 0.1.5
 */
public class SessionPauseResumeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SessionPauseResumeCoordinator.class);

    private static final long DEBOUNCE_MILLIS = 1000L;

    /** Maximum size of the debounce bookkeeping before it is reset wholesale. */
    private static final int DEBOUNCE_MAP_LIMIT = 10_000;

    /** Metadata key carrying the caller tenant id inside the pause intent. */
    static final String TENANT_ID_KEY = "tenant_id";

    /** Maximum number of terminated-session records the LRU registry retains. */
    private static final int TERMINATED_SET_LIMIT = 10_000;

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private final ActiveStreamInterruptor streamInterruptor;
    private final ObjectProvider<TaskAdmissionGate> admissionGateProvider;
    private final Executor pauseExecutor;
    private final Map<String, Long> lastPauseRequestMillis = new ConcurrentHashMap<>();
    private final Set<String> pausedSessions = ConcurrentHashMap.newKeySet();
    /** Insertion-ordered ids mirroring the entries of {@link #terminatedSessions} for LRU eviction. */
    private final ConcurrentLinkedDeque<String> terminatedOrder = new ConcurrentLinkedDeque<>();
    private final Set<String> terminatedSessions = ConcurrentHashMap.newKeySet();

    /**
     * Creates the coordinator.
     *
     * @param orchestratorProvider orchestrator provider for active-execution lookup
     * @param streamInterruptor shared interrupt chain used by terminate
     * @param admissionGateProvider admission gate provider; absent gate means admission always passes
     * @param pauseExecutor executor running the asynchronous interruption work
     */
    public SessionPauseResumeCoordinator(ObjectProvider<ServeOrchestrator> orchestratorProvider,
            ActiveStreamInterruptor streamInterruptor, ObjectProvider<TaskAdmissionGate> admissionGateProvider,
            Executor pauseExecutor) {
        this.orchestratorProvider = Objects.requireNonNull(orchestratorProvider, "orchestratorProvider");
        this.streamInterruptor = Objects.requireNonNull(streamInterruptor, "streamInterruptor");
        this.admissionGateProvider = Objects.requireNonNull(admissionGateProvider, "admissionGateProvider");
        this.pauseExecutor = Objects.requireNonNull(pauseExecutor, "pauseExecutor");
    }

    /**
     * Whether the configured orchestrator implements the issue #117 session
     * operations (pause/resume/compact). Exposed so the session management
     * service can reject the operations up front (HTTP 501) instead of
     * recording a paused state that no orchestrator will ever consume.
     *
     * @return {@code true} when the orchestrator supports the operations
     */
    public boolean supportsSessionOperations() {
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        return orchestrator != null && orchestrator.supportsSessionOperations();
    }

    /**
     * Pauses a running session: publishes the PAUSED intent synchronously and
     * interrupts the active execution asynchronously. Repeated requests within
     * the debounce window yield the idempotent {@code alreadyInState} result
     * without re-triggering the interrupt.
     *
     * @param sessionId target session id, never null or blank
     * @param operator operator identity for audit, may be null
     * @param reason pause reason for audit, may be null
     * @return orchestration result; {@code rejected} only for blank ids
     */
    public SessionOperationResult pause(String sessionId, String operator, String reason) {
        return pause(sessionId, operator, null, reason);
    }

    /**
     * Pauses a running session with the caller's tenant identity: the tenant
     * travels inside the pause intent metadata, so the handler publishing the
     * intent can resolve the same tenant-scoped registry key the executing
     * loop consumes under tenant-aware deployments.
     *
     * <p>Orchestrators without session-operations support are rejected
     * synchronously before any registry bookkeeping: publishing no intent
     * but interrupting anyway would leave the execution without a persisted
     * pause point - unresumable.</p>
     *
     * @param sessionId target session id, never null or blank
     * @param operator operator identity for audit, may be null
     * @param tenantId tenant identity of the calling tenant, may be null in
     *        tenant-less deployments
     * @param reason pause reason for audit, may be null
     * @return orchestration result; {@code rejected} for blank ids,
     *         unsupported orchestrators, or scheduling failures
     */
    public SessionOperationResult pause(String sessionId, String operator, String tenantId, String reason) {
        if (isBlank(sessionId)) {
            return SessionOperationResult.rejected(null, "sessionId is blank");
        }
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null || !orchestrator.supportsSessionOperations()) {
            return SessionOperationResult.rejected(SessionState.RUNNING,
                    "pause is not supported by the configured orchestrator");
        }
        long now = System.currentTimeMillis();
        Long lastRequest = lastPauseRequestMillis.get(sessionId);
        if (lastRequest != null && now - lastRequest < DEBOUNCE_MILLIS) {
            return SessionOperationResult.alreadyInState(SessionState.PAUSED,
                    "pause request merged within debounce window");
        }
        lastPauseRequestMillis.put(sessionId, now);
        boundClear(lastPauseRequestMillis, DEBOUNCE_MAP_LIMIT);
        pausedSessions.add(sessionId);
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.PAUSED,
                "pause accepted; execution will be interrupted at the next step boundary");
        try {
            pauseExecutor.execute(() -> doPause(sessionId, operator, tenantId, reason));
        } catch (RuntimeException exception) {
            log.warn("Pause scheduling failed for session_id={}: {}", sessionId, exception.getMessage());
            rollbackPausedState(sessionId, true);
            lastPauseRequestMillis.remove(sessionId);
            return SessionOperationResult.rejected(SessionState.RUNNING,
                    "pause scheduling failed; session keeps running and pause can be retried");
        }
        return accepted;
    }

    /**
     * Resumes a paused session: acquires an admission slot (gate full keeps the
     * session paused with a retryable rejection), then flips the session back
     * to RUNNING and continues the execution from the persisted PAUSED cursor.
     *
     * <p>The admission slot is released on every termination path of the
     * asynchronous continuation ({@link #doResume}); the resumed task runs
     * through the internal execution chain the gate does not track, so the
     * slot must not be held beyond the resume work itself.</p>
     *
     * @param sessionId target session id, never null or blank
     * @param operator operator identity for audit, may be null
     * @return orchestration result; {@code rejected} for blank ids, unsupported
     *         orchestrators, admission rejection, or state conflicts
     */
    public SessionOperationResult resume(String sessionId, String operator) {
        if (isBlank(sessionId)) {
            return SessionOperationResult.rejected(null, "sessionId is blank");
        }
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null || !orchestrator.supportsSessionOperations()) {
            return SessionOperationResult.rejected(SessionState.PAUSED,
                    "resume is not supported by the configured orchestrator");
        }
        TaskAdmissionGate gate = admissionGateProvider.getIfAvailable();
        if (gate != null && !gate.tryAcquire()) {
            log.info("Resume admission rejected for session_id={} (gate full), session stays paused", sessionId);
            return SessionOperationResult.rejected(SessionState.PAUSED,
                    "admission gate is full; session remains paused and resume can be retried");
        }
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.RUNNING,
                "resume accepted; continuation starts from the persisted paused boundary");
        try {
            pauseExecutor.execute(() -> doResume(sessionId, operator));
        } catch (RuntimeException exception) {
            log.warn("Resume scheduling failed for session_id={}: {}", sessionId, exception.getMessage());
            rollbackPausedState(sessionId, true);
            releaseAdmission(sessionId);
            return SessionOperationResult.rejected(SessionState.PAUSED,
                    "resume scheduling failed; session remains paused and resume can be retried");
        }
        return accepted;
    }

    /**
     * Returns the process-local management state of a session: PAUSED or
     * TERMINATED when this coordinator recorded the corresponding transition,
     * null otherwise (the caller falls back to the active-task registry for
     * RUNNING and the idle default).
     *
     * @param sessionId target session id
     * @return the recorded management state, or null when unrecorded
     */
    public SessionState managedState(String sessionId) {
        if (terminatedSessions.contains(sessionId)) {
            return SessionState.TERMINATED;
        }
        if (pausedSessions.contains(sessionId)) {
            return SessionState.PAUSED;
        }
        return null;
    }

    /**
     * Terminates a session: interrupts the active execution through the same
     * shared chain as pause, but the session state becomes TERMINATED and the
     * continuation path is closed. No pause intent is published - a
     * terminated session must never be resumable. Repeated requests are
     * idempotent.
     *
     * @param sessionId target session id, never null or blank
     * @param operator operator identity for audit, may be null
     * @return orchestration result; {@code rejected} only for blank ids
     */
    public SessionOperationResult terminate(String sessionId, String operator) {
        if (isBlank(sessionId)) {
            return SessionOperationResult.rejected(null, "sessionId is blank");
        }
        recordTerminated(sessionId);
        pausedSessions.remove(sessionId);
        lastPauseRequestMillis.remove(sessionId);
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.TERMINATED,
                "terminate accepted; active execution will be interrupted");
        try {
            pauseExecutor.execute(() -> doInterruptOnly(sessionId, operator));
        } catch (RuntimeException exception) {
            log.warn("Terminate scheduling failed for session_id={}: {}", sessionId, exception.getMessage());
            return SessionOperationResult.rejected(SessionState.TERMINATED,
                    "terminate scheduling failed; the interruption can be retried");
        }
        return accepted;
    }

    /**
     * Releases a previously acquired admission slot after a failed resume
     * continuation, so rejected or crashed resumes do not leak quota.
     *
     * @param sessionId target session id, for logging only
     */
    public void releaseAdmission(String sessionId) {
        TaskAdmissionGate gate = admissionGateProvider.getIfAvailable();
        if (gate != null) {
            gate.release();
            log.debug("Resume admission slot released for session_id={}", sessionId);
        }
    }

    /**
     * Compacts the persisted context of an idle session synchronously (issue
     * #117 section 3.3): the orchestrator forwards the compaction to the
     * handler, which restores the session from the checkpoint, runs the
     * compression processors, and persists the compacted state. Unlike
     * pause/resume this runs on the caller thread because the HTTP response
     * carries the compression outcome.
     *
     * @param sessionId session id of the idle session
     * @param operator operator identity for audit, may be null
     * @param options compression options (processor types, thresholds); may
     *                be null for the default strategy
     * @return the handler's compression outcome, never null
     * @throws IllegalStateException when no orchestrator is available, the
     *         orchestrator lacks session-operations support, or the
     *         compaction execution fails
     */
    public Map<String, Object> compactSession(String sessionId, String operator, Map<String, Object> options) {
        log.info("Compact requested: session_id={}, operator={}", sessionId, operator);
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            throw new IllegalStateException("No ServeOrchestrator available for session_id " + sessionId);
        }
        if (!orchestrator.supportsSessionOperations()) {
            throw new IllegalStateException("Session compaction is not supported by the configured orchestrator");
        }
        return orchestrator.compactSession(sessionId, options);
    }

    /**
     * Performs the asynchronous interruption work behind {@link #pause}: the
     * user pause intent is published to the handler first so the executing
     * agent loop persists the PAUSED cursor at its next step boundary, then
     * the shared interrupt chain cancels the active stream forwarding. The
     * core loop keeps running until the boundary (an in-flight model call
     * lands first), matching the terminate semantics for the cancel part.
     *
     * <p>The caller's tenant id travels inside the pause metadata so the
     * handler-side publication resolves the tenant-scoped registry key of the
     * session's tenant instead of the (tenant-less) operations-pool thread
     * context.</p>
     *
     * <p>A failed intent publication aborts the pause: interrupting without a
     * published intent would end the stream with no persisted PAUSED cursor,
     * leaving a session the registry marks paused but no resume point can
     * continue - so the execution keeps running and the recorded paused state
     * is rolled back. Callers see the pause accepted (202) but the session
     * simply stays running; the audit log records the failure.</p>
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     * @param tenantId tenant identity of the calling tenant, may be null
     * @param reason pause reason for audit
     */
    private void doPause(String sessionId, String operator, String tenantId, String reason) {
        log.info("Pause requested: session_id={}, operator={}, tenant_id={}, reason={}",
                sessionId, operator, tenantId, reason);
        boolean isIntentPublished = false;
        try {
            ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
            if (orchestrator != null) {
                Map<String, Object> pauseInfo = new LinkedHashMap<>();
                pauseInfo.put("operator", operator);
                pauseInfo.put("reason", reason);
                if (tenantId != null && !tenantId.isBlank()) {
                    pauseInfo.put(TENANT_ID_KEY, tenantId);
                }
                pauseInfo.put("requestedAt", System.currentTimeMillis());
                orchestrator.requestPause(sessionId, pauseInfo);
                isIntentPublished = true;
            } else {
                log.warn("No ServeOrchestrator available, pause intent skipped for session_id={}", sessionId);
            }
        } catch (RuntimeException exception) {
            log.warn("Pause intent publish failed for session_id={}: {}", sessionId, exception.getMessage());
        }
        if (!isIntentPublished) {
            // No durable pause point can exist without the intent; interrupting
            // anyway would trade a running session for an unresumable corpse.
            log.warn("Pause aborted without intent for session_id={}; session keeps running", sessionId);
            pausedSessions.remove(sessionId);
            // A retry within the debounce window must not be merged against a
            // pause that never took effect.
            lastPauseRequestMillis.remove(sessionId);
            return;
        }
        try {
            streamInterruptor.interrupt(sessionId, InterruptReason.USER_REQUEST);
        } catch (RuntimeException exception) {
            log.warn("Pause interrupt failed for session_id={}: {}", sessionId, exception.getMessage());
        }
    }

    /**
     * Performs the asynchronous interruption work behind {@link #terminate}:
     * only the shared cancel chain runs, no pause intent is published so a
     * terminated session can never be resumed from a stale intent.
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     */
    private void doInterruptOnly(String sessionId, String operator) {
        log.info("Terminate requested: session_id={}, operator={}", sessionId, operator);
        try {
            streamInterruptor.interrupt(sessionId, InterruptReason.LIFECYCLE_INTERRUPT);
        } catch (RuntimeException exception) {
            log.warn("Terminate interrupt failed for session_id={}: {}", sessionId, exception.getMessage());
        }
    }

    /**
     * Performs the asynchronous continuation work behind {@link #resume}: the
     * orchestrator restarts the session from the persisted paused boundary.
     * The admission slot is always released once the continuation work
     * finishes: the resumed task itself runs through the internal execution
     * chain that the gate's release tracking does not observe, so holding the
     * slot would leak quota one resume at a time.
     *
     * <p>The paused-state rollback only fires when this coordinator
     * previously recorded the pause: a session that was never paused here
     * (e.g. an idle session admitted through the resume path) must not be
     * flipped into the PAUSED registry state by a failed continuation.</p>
     *
     * @param sessionId target session id
     * @param operator operator identity for audit
     */
    private void doResume(String sessionId, String operator) {
        log.info("Resume requested: session_id={}, operator={}", sessionId, operator);
        boolean wasSessionPaused = pausedSessions.remove(sessionId);
        // A pause accepted after this resume must not merge against the stale
        // debounce timestamp of the pre-resume pause request.
        lastPauseRequestMillis.remove(sessionId);
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            log.warn("No ServeOrchestrator available, resume continuation skipped for session_id={}", sessionId);
            rollbackPausedState(sessionId, wasSessionPaused);
            releaseAdmission(sessionId);
            return;
        }
        try {
            orchestrator.resumeSession(sessionId);
        } catch (RuntimeException exception) {
            log.warn("Resume continuation failed for session_id={}: {}", sessionId, exception.getMessage());
            rollbackPausedState(sessionId, wasSessionPaused);
        } finally {
            releaseAdmission(sessionId);
        }
    }

    /**
     * Restores the recorded paused state of a session after a failed
     * transition, only when the transition had actually removed a previous
     * pause record.
     *
     * @param sessionId target session id
     * @param wasSessionPaused whether the session was recorded as paused
     *        before the transition removed the record
     */
    private void rollbackPausedState(String sessionId, boolean wasSessionPaused) {
        if (wasSessionPaused) {
            pausedSessions.add(sessionId);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Records a terminated session in the registry and evicts the oldest
     * record when the registry exceeds its limit. Unlike a wholesale reset,
     * per-entry eviction keeps every other terminated session final, so the
     * "terminated is terminal" state machine guarantee survives high session
     * turnover; only the least recently terminated record loses its
     * repeated-terminate idempotency.
     *
     * @param sessionId session id to record
     */
    private void recordTerminated(String sessionId) {
        terminatedSessions.add(sessionId);
        terminatedOrder.addLast(sessionId);
        while (terminatedSessions.size() > TERMINATED_SET_LIMIT) {
            String oldest = terminatedOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            terminatedSessions.remove(oldest);
        }
    }

    /**
     * Bounds the debounce bookkeeping wholesale: once the map exceeds the
     * limit it is emptied in one step. Losing debounce entries only widens
     * the merge window for one request, benign against unbounded
     * process-lifetime growth.
     *
     * @param <T> map value type
     * @param collection map to bound
     * @param limit maximum retained entries
     */
    private static <T> void boundClear(Map<String, T> collection, int limit) {
        if (collection.size() > limit) {
            log.warn("Session bookkeeping exceeded {} entries, resetting", limit);
            collection.clear();
        }
    }
}
