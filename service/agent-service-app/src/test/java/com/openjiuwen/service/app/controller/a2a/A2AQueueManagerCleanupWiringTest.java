/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Verifies the {@code a2aQueueManager} wiring: a task store that implements
 * {@code TaskStateProvider} drives per-task queue cleanup (the queue leaves the
 * manager's map once the task reaches a final state), while a plain store keeps
 * the legacy behavior of never auto-removing queues.
 */
class A2AQueueManagerCleanupWiringTest {
    @Test
    void providerBackedStoreRemovesQueueOnceTaskIsFinal() {
        RecordingStore delegate = new RecordingStore();
        WriteThrottlingTaskStore store = new WriteThrottlingTaskStore(delegate, 200L);
        InMemoryQueueManager manager = wiredManager(store);

        EventQueue queue = manager.createOrTap("task-1");
        assertThat(queue).isNotNull();
        assertThat(manager.get("task-1")).isNotNull();

        // Task not finalized yet: the cleanup callback must keep the queue.
        manager.getCleanupCallback("task-1").run();
        assertThat(manager.get("task-1")).isNotNull();

        // Final state is durable in the store: now the callback must remove it.
        store.save(finalizedTask("task-1"), false);
        manager.getCleanupCallback("task-1").run();
        assertThat(manager.get("task-1")).isNull();
    }

    @Test
    void plainStoreKeepsLegacyKeepQueueBehavior() {
        RecordingStore plain = new RecordingStore();
        InMemoryQueueManager manager = wiredManager(plain);

        manager.createOrTap("task-2");
        manager.getCleanupCallback("task-2").run();

        // No TaskStateProvider: the manager cannot observe finalization, so the
        // queue stays registered exactly as before.
        assertThat(manager.get("task-2")).isNotNull();
    }

    /**
     * Wires the auto-configuration's queue manager for the given store and returns
     * it typed as the expected in-memory implementation.
     *
     * @param store the task store to wire
     * @return the wired in-memory queue manager
     */
    private static InMemoryQueueManager wiredManager(TaskStore store) {
        QueueManager wired = new A2AAutoConfiguration().a2aQueueManager(store, new MainEventBus());
        if (wired instanceof InMemoryQueueManager manager) {
            return manager;
        }
        return fail("wiring must produce an InMemoryQueueManager");
    }

    private static Task finalizedTask(String id) {
        return Task.builder().id(id).contextId("ctx").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }

    /**
     * Minimal {@link TaskStore} fake without {@code TaskStateProvider}.
     */
    private static final class RecordingStore implements TaskStore {
        private final Map<String, Task> data = new HashMap<>();

        @Override
        public void save(Task task, boolean isOverwrite) {
            data.put(task.id(), task);
        }

        @Override
        public Task get(String taskId) {
            return data.get(taskId);
        }

        @Override
        public void delete(String taskId) {
            data.remove(taskId);
        }

        @Override
        public ListTasksResult list(ListTasksParams params) {
            return new ListTasksResult(new ArrayList<>(data.values()), data.size(), data.size(), null);
        }
    }
}
