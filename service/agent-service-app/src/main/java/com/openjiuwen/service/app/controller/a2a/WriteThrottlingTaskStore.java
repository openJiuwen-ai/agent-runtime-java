/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Read-through / write-behind cache in front of a slow {@link TaskStore} (e.g.
 * {@link RedisTaskStore}).
 * <p>
 * <b>Why this exists.</b> The A2A SDK's {@code MainEventBusProcessor} persists
 * the task on <em>every</em> event it
 * distributes, and streaming produces one {@code TaskArtifactUpdateEvent} per
 * LLM chunk. For each event the SDK builds a
 * fresh {@code TaskManager}, which does {@code taskStore.get()} -> merge the
 * single event -> {@code taskStore.save()}.
 * With an in-memory store those calls are effectively free, so the SSE stream
 * flows at LLM speed. With a Redis store
 * every chunk becomes a full task serialization plus a blocking network
 * round-trip on the single processor thread,
 * <em>before</em> the chunk is distributed to the client -- turning smooth
 * streaming into a slow trickle (and the cost is
 * quadratic, because the whole growing task is re-serialized per chunk).
 * <p>
 * <b>Why a plain "skip some writes" throttle is wrong.</b> Because the SDK
 * reads the task back from the store between
 * events, dropping intermediate writes would make the next {@code get()} return
 * a stale task and silently lose the
 * artifacts from the skipped writes. This decorator therefore keeps the latest
 * task in an in-memory map that
 * {@link #get(String)} serves first, so the SDK always sees the freshest state
 * regardless of what has reached Redis.
 * Only the durable backing write is throttled.
 * <p>
 * <b>Policy.</b> Non-terminal streaming saves (WORKING / SUBMITTED) are written
 * to the delegate at most once per
 * {@link #minWriteIntervalMs}; state transitions that matter for durability and
 * cross-process resume -- anything
 * {@link TaskState#isFinal() final} or {@link TaskState#isInterrupted()
 * interrupted} (e.g. {@code INPUT_REQUIRED}) -- are
 * always written through immediately. A mid-stream crash may lose at most the
 * last throttle window of partial artifacts,
 * which is acceptable because an unfinished streaming response is re-run by the
 * client anyway; the completed/interrupted
 * result is always durable.
 * <p>
 * <b>Queue lifecycle.</b> Implementing {@link TaskStateProvider} lets the SDK's
 * queue manager ask this store whether a
 * task is finished, so the per-task {@code MainQueue} is removed from the
 * manager's map once the task reaches a final
 * state. Reads are served from the read-through cache first, so this adds at
 * most one delegate read per finalized task.
 *
 * @since 0.1.0
 */
public class WriteThrottlingTaskStore implements TaskStore, TaskStateProvider {
    private static final Logger log = LoggerFactory.getLogger(WriteThrottlingTaskStore.class);

    private static final long DEFAULT_MIN_WRITE_INTERVAL_MS = 200L;

    private final TaskStore delegate;

    private final long minWriteIntervalMs;

    private final LongSupplier clock;

    /**
     * Latest task per id -- authoritative for reads while a task is active
     * (read-through cache).
     */
    private final ConcurrentMap<String, Task> latest = new ConcurrentHashMap<>();

    /**
     * Timestamp of the last durable write per id, used to throttle non-critical
     * writes.
     */
    private final ConcurrentMap<String, Long> lastWriteMs = new ConcurrentHashMap<>();

    public WriteThrottlingTaskStore(TaskStore delegate) {
        this(delegate, DEFAULT_MIN_WRITE_INTERVAL_MS, System::currentTimeMillis);
    }

    /**
     * Creates a throttling store with a configurable coalescing window.
     *
     * @param delegate the backing store that receives the durable writes
     * @param minWriteIntervalMs minimum interval in milliseconds between durable
     *        writes of non-terminal task snapshots; terminal and interrupted
     *        states always write through immediately. Values &lt;= 0 disable
     *        coalescing.
     * @since 0.1.2
     */
    public WriteThrottlingTaskStore(TaskStore delegate, long minWriteIntervalMs) {
        this(delegate, minWriteIntervalMs, System::currentTimeMillis);
    }

    WriteThrottlingTaskStore(TaskStore delegate, long minWriteIntervalMs, LongSupplier clock) {
        this.delegate = delegate;
        this.minWriteIntervalMs = minWriteIntervalMs;
        this.clock = clock;
    }

    @Override
    public void save(Task task, boolean isOverwrite) {
        String id = task.id();
        // Always refresh the in-memory copy first: get() serves this, so no artifact is
        // ever lost even when the
        // backing write below is throttled away.
        latest.put(id, task);

        TaskState state = task.status() != null ? task.status().state() : null;
        boolean isCritical = state == null || state.isFinal() || state.isInterrupted();
        long now = clock.getAsLong();
        Long prev = lastWriteMs.get(id);
        boolean isWriteDue = prev == null || (now - prev) >= minWriteIntervalMs;

        if (isCritical || isWriteDue) {
            delegate.save(task, isOverwrite);
            lastWriteMs.put(id, clock.getAsLong());
            if (state != null && (state.isFinal() || state.isInterrupted())) {
                // Durable in the delegate now; drop the in-memory copy so the map stays
                // bounded.  Both final and interrupted (e.g. INPUT_REQUIRED) states have
                // been persisted -- the in-memory cache is no longer needed as a
                // read-through shortcut and retaining it would leak memory for every
                // abandoned interrupted task.
                latest.remove(id);
                lastWriteMs.remove(id);
            }
            return;
        }

        // Throttled: within the window and not critical. The in-memory copy above is
        // enough, so there is nothing to persist now.
        if (log.isTraceEnabled()) {
            long sinceLastWrite = prev == null ? 0L : now - prev;
            log.trace("A2A task {} save coalesced (throttled, {}ms since last write)", id, sinceLastWrite);
        }
    }

    @Override
    public Task get(String taskId) {
        Task cached = latest.get(taskId);
        return cached != null ? cached : delegate.get(taskId);
    }

    @Override
    public void delete(String taskId) {
        latest.remove(taskId);
        lastWriteMs.remove(taskId);
        delegate.delete(taskId);
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        // Flush any coalesced-but-unwritten state so the delegate's scan reflects
        // in-flight tasks too.
        for (Map.Entry<String, Task> e : latest.entrySet()) {
            delegate.save(e.getValue(), true);
            lastWriteMs.put(e.getKey(), clock.getAsLong());
        }
        return delegate.list(params);
    }

    @Override
    public boolean isTaskActive(String taskId) {
        Task task = currentTask(taskId);
        if (task == null) {
            return false;
        }
        return task.status() == null || task.status().state() == null || !task.status().state().isFinal();
    }

    @Override
    public boolean isTaskFinalized(String taskId) {
        Task task = currentTask(taskId);
        return task != null && task.status() != null && task.status().state() != null
                && task.status().state().isFinal();
    }

    /**
     * Resolves the freshest task state: read-through cache first, delegate second.
     * A delegate read failure answers "unknown" so queue-lifecycle callers keep
     * their current keep-queue behavior instead of reacting to a transient store
     * outage.
     */
    private Task currentTask(String taskId) {
        Task cached = latest.get(taskId);
        if (cached != null) {
            return cached;
        }
        try {
            return delegate.get(taskId);
        } catch (Exception e) {
            log.debug("A2A task {} state lookup failed; treating as not finalized", taskId, e);
            return null;
        }
    }
}
