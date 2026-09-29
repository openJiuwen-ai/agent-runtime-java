/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

import java.util.Optional;

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

    /**
     * Return the active-task snapshot for one hosted agent.
     *
     * <p>The default implementation preserves compatibility for process-level
     * implementations that do not expose per-agent snapshots.</p>
     * <p>The snapshot contains only this agent's tasks, with their count as
     * {@code currentActiveTasks}. {@code maxConcurrentTasks} remains the shared
     * process limit, not an independent limit for the agent.</p>
     *
     * @param agentId hosted registration ID
     * @return the agent snapshot, or empty when per-agent querying is unavailable
     */
    default Optional<ConcurrencyLoadSnapshot> snapshot(String agentId) {
        return Optional.empty();
    }
}
