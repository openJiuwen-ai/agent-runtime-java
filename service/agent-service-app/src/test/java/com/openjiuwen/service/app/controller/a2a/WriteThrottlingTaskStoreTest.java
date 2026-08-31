/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskPersistenceException;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Verifies the read-through / write-behind policy that keeps A2A streaming fast
 * on a slow (Redis) task store: rapid
 * non-terminal saves are coalesced into the delegate, terminal/interrupted
 * saves always write through, and reads always
 * see the freshest task so no streamed artifact is ever lost to throttling.
 */
class WriteThrottlingTaskStoreTest {
    private static final String ID = "task-1";

    private static final long INTERVAL_MS = 200L;

    private final long[] now = {1_000L};

    private final LongSupplier clock = () -> now[0];

    private final CountingStore delegate = new CountingStore();

    private final WriteThrottlingTaskStore store = new WriteThrottlingTaskStore(delegate, INTERVAL_MS, clock);

    @Test
    void coalescesRapidWorkingSavesButKeepsCacheFresh() {
        // 50 streaming artifact updates within a single throttle window (clock does not
        // advance).
        for (int i = 0; i < 50; i++) {
            store.save(working("v" + i), false);
        }

        // Only the first save reached the delegate (one Redis round-trip instead of
        // 50).
        assertThat(delegate.saveCount).isEqualTo(1);
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("v0");

        // But reads see the latest version — throttling never loses artifacts.
        assertThat(store.get(ID).contextId()).isEqualTo("v49");
    }

    @Test
    void writesAgainOnceThrottleWindowElapses() {
        store.save(working("v0"), false);
        assertThat(delegate.saveCount).isEqualTo(1);

        now[0] += INTERVAL_MS; // window elapsed
        store.save(working("v1"), false);

        assertThat(delegate.saveCount).isEqualTo(2);
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("v1");
    }

    @Test
    void measuresThrottleWindowAfterSlowDelegateWriteCompletes() {
        delegate.saveDurationMs = INTERVAL_MS + 50L;

        store.save(working("v0"), false);
        store.save(working("v1"), false);

        assertThat(delegate.saveCount).isEqualTo(1);
        assertThat(store.get(ID).contextId()).isEqualTo("v1");
    }

    @Test
    void finalStateAlwaysWritesThroughAndEvictsCache() {
        store.save(working("v0"), false); // count 1
        // COMPLETED arrives inside the same throttle window — must still persist
        // immediately.
        store.save(withState("done", TaskState.TASK_STATE_COMPLETED), false);

        assertThat(delegate.saveCount).isEqualTo(2);
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("done");

        // Cache was evicted after the final write: a later read comes from the
        // delegate, not a stale in-memory copy.
        delegate.data.put(ID, withState("from-delegate", TaskState.TASK_STATE_COMPLETED));
        assertThat(store.get(ID).contextId()).isEqualTo("from-delegate");
    }

    @Test
    void interruptedStateAlwaysWritesThrough() {
        store.save(working("v0"), false); // count 1
        // INPUT_REQUIRED must be durable immediately so a cross-process resume can find
        // it.
        store.save(withState("await", TaskState.TASK_STATE_INPUT_REQUIRED), false);

        assertThat(delegate.saveCount).isEqualTo(2);
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("await");
    }

    @Test
    void listFlushesCoalescedStateToDelegate() {
        store.save(working("v0"), false); // written
        store.save(working("v1"), false); // coalesced (same window)
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("v0");

        store.list(new ListTasksParams());

        // list() flushes the latest in-flight state so a scan reflects it.
        assertThat(delegate.data.get(ID).contextId()).isEqualTo("v1");
    }

    @Test
    void taskStateProviderMirrorsTaskLifecycle() {
        // Unknown task: neither active nor finalized.
        assertThat(store.isTaskActive(ID)).isFalse();
        assertThat(store.isTaskFinalized(ID)).isFalse();

        // Working task lives in the read-through cache only: active, not finalized.
        store.save(working("v0"), false);
        assertThat(store.isTaskActive(ID)).isTrue();
        assertThat(store.isTaskFinalized(ID)).isFalse();

        // Final task evicts the cache and writes through: finalized, not active.
        store.save(withState("done", TaskState.TASK_STATE_COMPLETED), false);
        assertThat(store.isTaskActive(ID)).isFalse();
        assertThat(store.isTaskFinalized(ID)).isTrue();
    }

    @Test
    void taskStateLookupSurvivesDelegateFailure() {
        delegate.getFailure = new TaskPersistenceException(ID, "redis down");

        // A transient delegate outage must answer "unknown" instead of throwing,
        // so queue-lifecycle callers keep their keep-queue behavior.
        assertThat(store.isTaskActive(ID)).isFalse();
        assertThat(store.isTaskFinalized(ID)).isFalse();
    }

    @Test
    void publicConstructorAppliesConfiguredWindow() {
        WriteThrottlingTaskStore wide = new WriteThrottlingTaskStore(delegate, 60_000L);

        wide.save(working("a"), false);
        wide.save(working("b"), false);

        // First save is due (no previous write); the second one is coalesced.
        assertThat(delegate.saveCount).isEqualTo(1);
        assertThat(wide.get(ID).contextId()).isEqualTo("b");
    }

    private static Task working(String versionTag) {
        return withState(versionTag, TaskState.TASK_STATE_WORKING);
    }

    private static Task withState(String versionTag, TaskState state) {
        return Task.builder().id(ID).contextId(versionTag).status(new TaskStatus(state)).build();
    }

    /**
     * In-memory {@link TaskStore} that counts writes, standing in for the slow
     * Redis delegate.
     */
    private final class CountingStore implements TaskStore {
        private final Map<String, Task> data = new HashMap<>();

        private int saveCount;

        private long saveDurationMs;

        private TaskPersistenceException getFailure;

        @Override
        public void save(Task task, boolean isOverwrite) {
            saveCount++;
            data.put(task.id(), task);
            now[0] += saveDurationMs;
        }

        @Override
        public Task get(String taskId) {
            if (getFailure != null) {
                throw getFailure;
            }
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
