/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.app.controller.a2a;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded task-bound input mailbox. It never invokes agents or inspects remote state.
 * The execution owner must finish its round before handing off its lease.
 */
final class A2ADeferredInputQueue<T> implements AutoCloseable {
    private final int maxSessions;
    private final int maxInputs;
    private final Map<TaskKey, Slot<T>> slots = new LinkedHashMap<>();
    private boolean closed;

    A2ADeferredInputQueue(int maxSessions, int maxInputs) {
        if (maxSessions <= 0 || maxInputs <= 0) {
            throw new IllegalArgumentException("Mailbox bounds must be positive");
        }
        this.maxSessions = maxSessions;
        this.maxInputs = maxInputs;
    }

    record Scope(String tenant, String space, String agent, String user, String conversation) {
        Scope {
            Objects.requireNonNull(tenant, "tenant");
            requireIdentity(space, "space");
            requireIdentity(agent, "agent");
            requireIdentity(user, "user");
            requireIdentity(conversation, "conversation");
        }
    }

    private record TaskKey(Scope scope, String taskId) {
    }

    enum Offer {
        ACCEPTED, DUPLICATE, NO_TARGET, FULL, CLOSED
    }

    /** Unforgeable round ownership; stale owners cannot release a later round. */
    static final class Lease<T> {
        private final TaskKey key;
        private final T input;

        private Lease(TaskKey key, T input) {
            this.key = key;
            this.input = input;
        }

        T input() {
            return input;
        }
    }

    synchronized void discardIdle(Scope scope, String taskId) {
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        if (slot != null && slot.taskId.equals(taskId) && slot.owner == null) {
            slots.remove(new TaskKey(scope, taskId));
        }
    }

    synchronized boolean hasIdleInput(Scope scope, String taskId) {
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        return !closed && slot != null && slot.taskId.equals(taskId) && slot.owner == null && !slot.pending.isEmpty();
    }

    synchronized Optional<String> task(Scope scope) {
        if (closed) {
            return Optional.empty();
        }
        var matches = slots.keySet().stream().filter(key -> key.scope().equals(scope)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0).taskId()) : Optional.empty();
    }

    synchronized boolean hasScope(Scope scope) {
        return slots.keySet().stream().anyMatch(key -> key.scope().equals(scope));
    }

    synchronized boolean idle(Scope scope, String taskId) {
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        return slot != null && slot.owner == null;
    }

    /** Claims an ordinary round without overtaking already accepted input. */
    synchronized Optional<Lease<T>> begin(Scope scope, String taskId) {
        Objects.requireNonNull(scope, "scope");
        requireIdentity(taskId, "taskId");
        if (closed) {
            return Optional.empty();
        }
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        if (slot == null) {
            if (!makeRoom()) {
                return Optional.empty();
            }
            slot = new Slot<>(taskId);
            slots.put(new TaskKey(scope, taskId), slot);
        }
        if (!slot.taskId.equals(taskId) || slot.owner != null || !slot.pending.isEmpty()) {
            return Optional.empty();
        }
        slot.owner = new Lease<>(new TaskKey(scope, taskId), null);
        return Optional.of(slot.owner);
    }

    private boolean makeRoom() {
        if (slots.size() < maxSessions) {
            return true;
        }
        var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Slot<T> slot = iterator.next().getValue();
            if (slot.owner == null && slot.pending.isEmpty()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    synchronized Set<String> accepted(Scope scope, String taskId) {
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        return slot == null ? Set.of() : Set.copyOf(slot.accepted);
    }

    /** Rebuild only an idle, server-validated waiting task. Never replace a concurrent owner. */
    synchronized boolean restore(Scope scope, String taskId, Set<String> accepted) {
        TaskKey key = new TaskKey(scope, taskId);
        if (closed || accepted.size() > maxInputs) {
            return false;
        }
        if (slots.containsKey(key)) {
            return true;
        }
        if (!makeRoom()) {
            return false;
        }
        Slot<T> slot = new Slot<>(taskId);
        slot.accepted.addAll(accepted);
        slots.put(key, slot);
        return true;
    }

    synchronized void cancelTask(String taskId) {
        var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().taskId().equals(taskId)) {
                Slot<T> slot = entry.getValue();
                slot.cancelled = true;
                slot.pending.clear();
                if (slot.owner == null) {
                    iterator.remove();
                }
            }
        }
        notifyAll();
    }

    /** A client may observe INPUT_REQUIRED before the previous producer finishes cleanup. */
    synchronized Optional<Lease<T>> resume(Scope scope, String taskId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        TaskKey key = new TaskKey(scope, taskId);
        while (!closed) {
            Slot<T> slot = slots.get(key);
            if (slot == null || slot.owner == null) {
                return begin(scope, taskId);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Stores input only for a bound task. Repeated message IDs never execute twice. */
    synchronized Offer offer(Scope scope, String taskId, String messageId, T input) {
        Objects.requireNonNull(scope, "scope");
        requireIdentity(taskId, "taskId");
        requireIdentity(messageId, "messageId");
        Objects.requireNonNull(input, "input");
        if (closed) {
            return Offer.CLOSED;
        }
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        if (slot == null || slot.cancelled || !slot.taskId.equals(taskId)) {
            return Offer.NO_TARGET;
        }
        if (slot.accepted.contains(messageId)) {
            return Offer.DUPLICATE;
        }
        // Bound both pending data and deduplication history; never evict accepted IDs.
        if (slot.pending.size() >= maxInputs || slot.accepted.size() >= maxInputs) {
            return Offer.FULL;
        }
        slot.accepted.add(messageId);
        slot.pending.addLast(input);
        return Offer.ACCEPTED;
    }

    /** Claims pending input for an idle task; caller owns dispatch and output handling. */
    synchronized Optional<Lease<T>> claim(Scope scope, String taskId) {
        Slot<T> slot = slots.get(new TaskKey(scope, taskId));
        if (closed || slot == null || !slot.taskId.equals(taskId) || slot.owner != null) {
            return Optional.empty();
        }
        return next(new TaskKey(scope, taskId), slot);
    }

    /** Transfers to queued input while retaining exclusive ownership when no input exists. */
    synchronized Optional<Lease<T>> advance(Lease<T> lease) {
        Slot<T> slot = slots.get(lease.key);
        if (closed || slot == null || slot.owner != lease || slot.pending.isEmpty()) {
            return Optional.empty();
        }
        return next(lease.key, slot);
    }

    /** Releases the owner and atomically hands off pending input, or discards it on termination. */
    synchronized Optional<Lease<T>> finish(Lease<T> lease, boolean terminal) {
        Objects.requireNonNull(lease, "lease");
        Slot<T> slot = slots.get(lease.key);
        if (closed || slot == null || slot.owner != lease) {
            return Optional.empty();
        }
        notifyAll();
        if (terminal || slot.cancelled) {
            slots.remove(lease.key);
            return Optional.empty();
        }
        slot.owner = null;
        return next(lease.key, slot);
    }

    synchronized boolean release(Lease<T> lease, boolean terminal) {
        Slot<T> slot = slots.get(lease.key);
        if (closed || slot == null || slot.owner != lease) {
            return false;
        }
        notifyAll();
        if (terminal || slot.cancelled) {
            slots.remove(lease.key);
            return false;
        }
        slot.owner = null;
        return !slot.pending.isEmpty();
    }

    /** Returns unexecuted input to the head when admission rejects its claimed round. */
    synchronized void retry(Lease<T> lease) {
        Slot<T> slot = slots.get(lease.key);
        if (!closed && slot != null && slot.owner == lease && lease.input != null) {
            slot.pending.addFirst(lease.input);
            slot.owner = null;
        }
    }

    private Optional<Lease<T>> next(TaskKey key, Slot<T> slot) {
        T input = slot.pending.pollFirst();
        if (input == null) {
            return Optional.empty();
        }
        slot.owner = new Lease<>(key, input);
        return Optional.of(slot.owner);
    }

    @Override
    public synchronized void close() {
        closed = true;
        slots.clear();
        notifyAll();
    }

    private static void requireIdentity(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class Slot<T> {
        private final String taskId;
        private final ArrayDeque<T> pending = new ArrayDeque<>();
        private final Set<String> accepted = new HashSet<>();
        private Lease<T> owner;
        private boolean cancelled;

        private Slot(String taskId) {
            this.taskId = taskId;
        }
    }
}
