/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

/**
 * Task-level admission gate for concurrency control (DFX-002).
 *
 * <p>Defined in the base module so that {@code A2aJsonRpcController} and
 * {@code CustomRestA2ABridge} can inject it via
 * {@code ObjectProvider<TaskAdmissionGate>} without depending on the ext
 * module's concrete implementation.
 *
 * @since 0.1.2
 */
public interface TaskAdmissionGate {
    /**
     * Attempt to acquire a task concurrency quota slot.
     *
     * @return {@code true} if the slot was acquired; {@code false} if the
     *         configured limit has been reached
     */
    boolean tryAcquire();

    /**
     * Release a previously acquired quota slot.
     */
    void release();

    /**
     * Current number of occupied quota slots.
     *
     * @return current active task count
     */
    int currentCount();

    /**
     * Configured concurrency limit, used by read-only admission pre-checks
     * (e.g. {@code currentCount() >= limit()} implies overload).
     *
     * @return the maximum number of concurrent tasks; {@code -1} means
     *         unlimited (implementations that do not track a limit should
     *         keep the default)
     */
    default int limit() {
        return -1;
    }

    /**
     * Shut down the gate — reject all new requests. Reserved for drain-phase
     * integration; not wired in the current version.
     */
    void shutdown();

    /**
     * Reset the gate to its initial state. Reserved for drain-phase
     * integration; not wired in the current version.
     */
    void reset();
}
