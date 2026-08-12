/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

/**
 * Query interface for current concurrency load (DFX-002).
 *
 * <p>Defined in the base module so that {@code ActiveTaskController} can
 * inject it via {@code ObjectProvider<ActiveTaskQuery>} without depending
 * on the ext module's {@code TaskQuotaTracker}.
 *
 * @since 0.1.2
 */
public interface ActiveTaskQuery {

    /**
     * Return a snapshot of the current concurrency load.
     *
     * @return a snapshot containing the configured max, current active count
     *         and the list of active tasks
     */
    ConcurrencyLoadSnapshot snapshot();
}
