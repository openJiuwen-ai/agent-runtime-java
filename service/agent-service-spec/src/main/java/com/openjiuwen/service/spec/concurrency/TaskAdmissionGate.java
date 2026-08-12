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
