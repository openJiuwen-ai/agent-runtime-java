/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

import java.util.Optional;

/**
 * Front-of-pipeline admission service for execution entry points (DFX-006).
 *
 * <p>Lets transport bridges (agent-bus, custom REST) and the JSON-RPC
 * controller run the unified admission classification and acquire a permit
 * authoritatively before the request enters the A2A SDK pipeline: a
 * rejection at this boundary creates no task, emits no business events and
 * never reaches downstream systems. The release side of the acquired permit
 * is owned by the runtime's admission-release coordinator, so this interface
 * only exposes the acquire/compensate pair.
 *
 * <p>Handover pattern: on success the caller stores
 * {@link #HANDOVER_MARKER_KEY} in the {@code ServerCallContext} state so the
 * agent executor adopts the already-held permit instead of acquiring a
 * second one; when the request fails synchronously before adoption, the
 * caller removes the marker atomically and calls {@link #release()} as
 * compensation — exactly one of the two sides releases.
 *
 * @since 0.1.4
 */
public interface TaskAdmissionService {
    /** Overload business code: every concurrency permit is held by a running round. */
    String BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED = "CONCURRENCY_LIMIT_REACHED";

    /** Overload business code: a held permit belongs to a round awaiting event landing. */
    String BUSINESS_CODE_EVENT_QUEUE_OVERLOADED = "EVENT_QUEUE_OVERLOADED";

    /** Overload business code: event-processing safety state cannot be determined. */
    String BUSINESS_CODE_EVENT_QUEUE_STATE_UNAVAILABLE = "EVENT_QUEUE_STATE_UNAVAILABLE";

    /**
     * {@code ServerCallContext} state key set by a transport entry point after
     * it has already acquired an admission permit for the request. The agent
     * executor's legacy constant refers to this single source of truth, so
     * the marker value cannot drift across modules.
     */
    String HANDOVER_MARKER_KEY = "_a2a_admission_preacquired";

    /**
     * Event-safety check, the first classification level: detects a stalled
     * event processor and rejects conservatively while non-execution entries
     * stay available.
     *
     * @return {@link #BUSINESS_CODE_EVENT_QUEUE_STATE_UNAVAILABLE} when the
     *         event-processing safety state cannot be trusted; empty when safe
     */
    Optional<String> checkEventSafety();

    /**
     * Attribution after {@link #tryAcquire()} failed, the second
     * classification level.
     *
     * @return {@link #BUSINESS_CODE_EVENT_QUEUE_OVERLOADED} when at least one
     *         held permit belongs to a round awaiting event landing, otherwise
     *         {@link #BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED}
     */
    String classifyPoolExhaustion();

    /**
     * Authoritatively acquires one admission permit for the caller.
     *
     * @return {@code true} when the permit was acquired
     */
    boolean tryAcquire();

    /**
     * Compensating release for a permit acquired via {@link #tryAcquire()},
     * used when the request fails synchronously before the agent executor
     * adopts it. Strictly paired with a successful acquire.
     */
    void release();
}
