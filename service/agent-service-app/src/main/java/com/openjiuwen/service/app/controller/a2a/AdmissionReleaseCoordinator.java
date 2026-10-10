/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionService;

import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Round-lease admission-release coordinator (DFX-006).
 *
 * <p>Owns the permit lifecycle of an admitted execution round under the
 * consequence-landed release model: a permit is held from admission until
 * the round's terminal or interrupted event has been consumed by the main
 * event processor ({@code consequenceLanded}), with a TTL fallback that
 * reclaims permits whose consequences were lost. Also provides the unified
 * two-level admission classification (event-safety stall check, then
 * pool-exhaustion attribution) and implements {@link TaskAdmissionService}
 * for the transport bridges' front-of-pipeline admission.
 *
 * <p>Modes: the full implementation is active when a gate is present and its
 * limit is {@code >= 0}; a pass-through implementation covers the remaining
 * assembled forms — gate present with limit {@code -1} degrades
 * {@code completeRound} to the DFX-002 immediate-release semantics (listener
 * pairing and logging unchanged), and a missing gate is a full no-op.
 *
 * @since 0.1.4
 */
public class AdmissionReleaseCoordinator implements TaskAdmissionService {
    /** Event-processor stall deadline: larger than the processor's 5s restart back-off. */
    static final long STALL_DEADLINE_MS = 30_000L;

    /** Re-arm step when a TTL check finds the consequences still pending. */
    static final long RESORT_STEP_MS = 60_000L;

    /** Force-cap floor; the effective cap is max(floor, 2x TTL). */
    static final long FORCE_CAP_FLOOR_MS = 20 * 60_000L;

    /** Default admission-release TTL when properties are absent. */
    static final long DEFAULT_TTL_MS = 10 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(AdmissionReleaseCoordinator.class);

    private final TaskAdmissionGate gate;

    private final MainEventBus mainEventBus;

    private final TaskStateProvider taskStateProvider;

    private final TaskAdmissionListener listener;

    private final Timing timing;

    private final boolean isFullMode;

    private final ConcurrentHashMap<String, Deque<ReleaseLease>> leases = new ConcurrentHashMap<>();

    private final ScheduledExecutorService ttlScheduler;

    private volatile long heartbeatAt = System.currentTimeMillis();

    /**
     * Production constructor.
     *
     * @param gate the admission gate; null disables admission control
     * @param mainEventBus the main event bus used for stall detection
     * @param taskStateProvider the task store's final-state provider; null
     *        degrades the TTL condition to deque-drain plus force cap
     * @param properties the A2A properties carrying the release TTL
     * @param listener the admission lifecycle listener
     */
    public AdmissionReleaseCoordinator(TaskAdmissionGate gate, MainEventBus mainEventBus,
            TaskStateProvider taskStateProvider, A2AProperties properties, TaskAdmissionListener listener) {
        this(gate, mainEventBus, taskStateProvider, listener, defaultTiming(properties));
    }

    /**
     * Assembly and test constructor with an explicit timing seam.
     *
     * @param gate the admission gate; null disables admission control
     * @param mainEventBus the main event bus used for stall detection
     * @param taskStateProvider the task store's final-state provider; null
     *        degrades the TTL condition to deque-drain plus force cap
     * @param listener the admission lifecycle listener
     * @param timing the explicit timing for the coordinator
     */
    AdmissionReleaseCoordinator(TaskAdmissionGate gate, MainEventBus mainEventBus,
            TaskStateProvider taskStateProvider, TaskAdmissionListener listener, Timing timing) {
        this.gate = gate;
        this.mainEventBus = mainEventBus;
        this.taskStateProvider = taskStateProvider;
        this.listener = listener;
        this.timing = timing;
        this.isFullMode = gate != null && gate.limit() >= 0;
        this.ttlScheduler = isFullMode ? new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("a2a-admission-release-ttl");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((source, error) ->
                    log.error("Uncaught admission release TTL thread={}", source.getName(), error));
            return thread;
        }) : null;
    }

    /** Assembly and test timing seam, in milliseconds. */
    record Timing(long ttlMs, long resortStepMs, long forceCapMs, long stallDeadlineMs) {
    }

    /** Round lease: one admitted execution round awaiting its consequence landing. */
    static final class ReleaseLease {
        final String taskId;

        final String conversationId;

        final AtomicBoolean released = new AtomicBoolean(false);

        volatile boolean isPendingRelease;

        final long admittedAt = System.currentTimeMillis();

        volatile long executionDoneAt = -1L;

        ReleaseLease(String taskId, String conversationId) {
            this.taskId = taskId;
            this.conversationId = conversationId;
        }
    }

    /**
     * Force-cap identity derivation: max(20m, 2x TTL), not independently
     * configurable so it can never fire before the first TTL check.
     *
     * @param ttlMs the configured admission-release TTL in milliseconds
     * @return the force cap in milliseconds
     */
    static long forceCapFor(long ttlMs) {
        return Math.max(FORCE_CAP_FLOOR_MS, 2 * ttlMs);
    }

    /**
     * Production timing derived from properties and the class constants.
     *
     * @param properties the A2A properties, may be null
     * @return the effective timing
     */
    static Timing defaultTiming(A2AProperties properties) {
        long ttlMs = DEFAULT_TTL_MS;
        if (properties != null && properties.getAdmissionReleaseTtl() != null) {
            ttlMs = properties.getAdmissionReleaseTtl().toMillis();
        }
        return new Timing(ttlMs, RESORT_STEP_MS, forceCapFor(ttlMs), STALL_DEADLINE_MS);
    }

    /**
     * Stops the TTL fallback scheduler. Pending leases are deliberately not
     * released — they belong to a shutting-down runtime; intended for Spring
     * context close.
     */
    public void shutdown() {
        ScheduledExecutorService scheduler = ttlScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public Optional<String> checkEventSafety() {
        if (!isFullMode || mainEventBus == null) {
            return Optional.empty();
        }
        boolean isStalled = mainEventBus.size() > 0
                && System.currentTimeMillis() - heartbeatAt > timing.stallDeadlineMs();
        return isStalled ? Optional.of(BUSINESS_CODE_EVENT_QUEUE_STATE_UNAVAILABLE) : Optional.empty();
    }

    @Override
    public String classifyPoolExhaustion() {
        if (isFullMode && pendingReleaseCount() > 0) {
            return BUSINESS_CODE_EVENT_QUEUE_OVERLOADED;
        }
        return BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED;
    }

    @Override
    public boolean tryAcquire() {
        if (gate == null) {
            return true;
        }
        return gate.tryAcquire();
    }

    @Override
    public void release() {
        if (gate != null) {
            gate.release();
        }
    }

    /**
     * Registers a round lease after the executor has acquired the permit.
     * The returned handle pins the round identity: {@link #completeRound(ReleaseLease)}
     * marks exactly this lease, so fast cross-round continuations cannot
     * misattribute a completion to another round's lease.
     *
     * @param taskId the task identifier of the admitted round
     * @param conversationId the conversation identifier for listener fidelity
     * @return the round lease handle; empty when nothing to track
     *         (no gate, or a null task id) — callers skip
     *         {@link #completeRound(ReleaseLease)} for an empty handle
     */
    Optional<ReleaseLease> register(String taskId, String conversationId) {
        if (taskId == null || gate == null) {
            return Optional.empty();
        }
        ReleaseLease lease = new ReleaseLease(taskId, conversationId);
        if (!isFullMode) {
            return Optional.of(lease);
        }
        leases.compute(taskId, (key, roundLeases) -> {
            Deque<ReleaseLease> deque = roundLeases != null ? roundLeases : new LinkedBlockingDeque<>();
            deque.addLast(lease);
            return deque;
        });
        return Optional.of(lease);
    }

    /**
     * Marks the round as execution-done and schedules the TTL fallback. The
     * round identity comes from the {@link #register(String, String)} handle,
     * so the completion lands on exactly the lease that was registered — even
     * when a newer round of the same task registered meanwhile. Idempotent
     * per handle; a lease already released by consequence landing is left
     * untouched. Must never release the gate itself.
     *
     * @param lease the round lease handle returned by
     *        {@link #register(String, String)}; {@code null} is a no-op
     */
    void completeRound(ReleaseLease lease) {
        if (lease == null || gate == null) {
            return;
        }
        if (!isFullMode) {
            releaseLease(lease);
            return;
        }
        if (!lease.isPendingRelease && !lease.released.get()) {
            lease.isPendingRelease = true;
            lease.executionDoneAt = System.currentTimeMillis();
            scheduleTtlCheck(lease);
        }
    }

    /**
     * Processor callback: the round's oldest un-released consequence has
     * landed, release it (exactly once, CAS-guarded).
     *
     * @param taskId the task identifier whose consequence landed
     */
    public void consequenceLanded(String taskId) {
        if (!isFullMode || taskId == null) {
            return;
        }
        Deque<ReleaseLease> roundLeases = leases.get(taskId);
        if (roundLeases == null) {
            return;
        }
        ReleaseLease lease = roundLeases.peekFirst();
        if (lease != null) {
            releaseLease(lease);
        }
    }

    /**
     * Processor callback: refreshes the consumption heartbeat.
     */
    public void heartbeat() {
        heartbeatAt = System.currentTimeMillis();
    }

    /**
     * Number of leases whose execution finished but whose consequences have
     * not landed yet — the pool-exhaustion attribution input.
     *
     * @return the pending-release count
     */
    public int pendingReleaseCount() {
        if (!isFullMode) {
            return 0;
        }
        int count = 0;
        for (Deque<ReleaseLease> roundLeases : leases.values()) {
            for (ReleaseLease lease : roundLeases) {
                if (lease.isPendingRelease) {
                    count++;
                }
            }
        }
        return count;
    }

    private void scheduleTtlCheck(ReleaseLease lease) {
        if (timing.ttlMs() <= 0) {
            return;
        }
        ttlScheduler.schedule(() -> checkTtl(lease), timing.ttlMs(), TimeUnit.MILLISECONDS);
    }

    private void checkTtl(ReleaseLease lease) {
        if (lease.released.get()) {
            return;
        }
        if (taskFinalizedOrDequeDrained(lease.taskId)) {
            releaseLease(lease);
            return;
        }
        if (System.currentTimeMillis() - lease.executionDoneAt >= timing.forceCapMs()) {
            releaseLease(lease);
            return;
        }
        ttlScheduler.schedule(() -> checkTtl(lease), timing.resortStepMs(), TimeUnit.MILLISECONDS);
    }

    private boolean taskFinalizedOrDequeDrained(String taskId) {
        if (taskStateProvider != null && taskStateProvider.isTaskFinalized(taskId)) {
            return true;
        }
        return mainEventBus == null || mainEventBus.size() <= 0;
    }

    private void releaseLease(ReleaseLease lease) {
        if (!lease.released.compareAndSet(false, true)) {
            return;
        }
        leases.compute(lease.taskId, (key, roundLeases) -> {
            if (roundLeases != null) {
                roundLeases.remove(lease);
                return roundLeases.isEmpty() ? null : roundLeases;
            }
            return null;
        });
        if (gate != null) {
            gate.release();
        }
        if (listener != null) {
            listener.onReleased(lease.taskId, lease.conversationId);
        }
        log.info("[CONCURRENCY] task_released taskId={} conversationId={} currentActive={} maxConcurrent={}",
                lease.taskId, lease.conversationId, gate != null ? gate.currentCount() : -1,
                gate != null ? gate.limit() : -1);
    }
}
