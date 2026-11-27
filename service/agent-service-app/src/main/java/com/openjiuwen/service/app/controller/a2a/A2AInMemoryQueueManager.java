/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;

/**
 * Skips task state lookup when queue cleanup has already removed the queue.
 *
 * @since 0.1.5
 */
public final class A2AInMemoryQueueManager extends InMemoryQueueManager {
    public A2AInMemoryQueueManager(TaskStateProvider taskStateProvider, MainEventBus mainEventBus) {
        super(taskStateProvider, mainEventBus);
    }

    @Override
    public Runnable getCleanupCallback(String taskId) {
        Runnable cleanup = super.getCleanupCallback(taskId);
        return () -> {
            // SDK close(taskId) removes the queue before invoking its close callbacks.
            if (get(taskId) != null) {
                cleanup.run();
            }
        };
    }
}
