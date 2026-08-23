/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

import java.util.List;

/**
 * Immutable snapshot of the current concurrency load (DFX-002).
 *
 * @since 0.1.2
 */
public final class ConcurrencyLoadSnapshot {
    private final int maxConcurrentTasks;

    private final int currentActiveTasks;

    private final List<ActiveTaskInfo> tasks;

    /**
     * Creates a snapshot.
     *
     * @param maxConcurrentTasks configured maximum (-1 means unlimited)
     * @param currentActiveTasks current number of active tasks
     * @param tasks list of active task details
     */
    public ConcurrencyLoadSnapshot(int maxConcurrentTasks, int currentActiveTasks,
            List<ActiveTaskInfo> tasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.currentActiveTasks = currentActiveTasks;
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /**
     * Configured maximum concurrent tasks. {@code -1} means unlimited.
     *
     * @return max concurrent tasks
     */
    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    /**
     * Current number of active tasks occupying quota.
     *
     * @return current active task count
     */
    public int getCurrentActiveTasks() {
        return currentActiveTasks;
    }

    /**
     * List of currently active tasks.
     *
     * @return immutable list of active task info
     */
    public List<ActiveTaskInfo> getTasks() {
        return tasks;
    }
}
