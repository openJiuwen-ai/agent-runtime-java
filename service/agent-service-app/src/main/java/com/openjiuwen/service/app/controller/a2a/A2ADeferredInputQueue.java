/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;

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
final class A2ADeferredInputQueue implements AutoCloseable {
    private final int maxSessions;
    private final int maxInputs;
    private final Map<TaskKey, Slot> slots = new LinkedHashMap<>();
    private boolean isClosed;

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

    /**
     * Unforgeable round ownership; stale owners cannot release a later round.
     */
    static final class Lease {
        private final TaskKey key;
        private final ServeRequest input;

        private Lease(TaskKey key, ServeRequest input) {
            this.key = key;
            this.input = input;
        }

        ServeRequest input() {
            return input;
        }
    }

    synchronized void discardIdle(Scope scope, String taskId) {
        Slot slot = slots.get(new TaskKey(scope, taskId));
        if (slot != null && slot.taskId.equals(taskId) && slot.owner == null) {
            slots.remove(new TaskKey(scope, taskId));
        }
    }

    synchronized boolean hasIdleInput(Scope scope, String taskId) {
        Slot slot = slots.get(new TaskKey(scope, taskId));
        return !isClosed && slot != null && slot.taskId.equals(taskId) && slot.owner == null && !slot.pending.isEmpty();
    }

    synchronized Optional<String> task(Scope scope) {
        if (isClosed) {
            return Optional.empty();
        }
        var matches = slots.keySet().stream().filter(key -> key.scope().equals(scope)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0).taskId()) : Optional.empty();
    }

    synchronized boolean hasScope(Scope scope) {
        return slots.keySet().stream().anyMatch(key -> key.scope().equals(scope));
    }

    synchronized boolean idle(Scope scope, String taskId) {
        Slot slot = slots.get(new TaskKey(scope, taskId));
        return slot != null && slot.owner == null;
    }

    /**
     * Claims an ordinary round without overtaking already accepted input.
     *
     * @param scope authenticated conversation scope
     * @param taskId task identity
     * @return exclusive round ownership, or empty when unavailable
     */
    synchronized Optional<Lease> begin(Scope scope, String taskId) {
        Objects.requireNonNull(scope, "scope");
        requireIdentity(taskId, "taskId");
        if (isClosed) {
            return Optional.empty();
        }
        Slot slot = slots.get(new TaskKey(scope, taskId));
        if (slot == null) {
            if (!makeRoom()) {
                return Optional.empty();
            }
            slot = new Slot(taskId);
            slots.put(new TaskKey(scope, taskId), slot);
        }
        if (!slot.taskId.equals(taskId) || slot.owner != null || !slot.pending.isEmpty()) {
            return Optional.empty();
        }
        slot.owner = new Lease(new TaskKey(scope, taskId), null);
        return Optional.of(slot.owner);
    }

    private boolean makeRoom() {
        if (slots.size() < maxSessions) {
            return true;
        }
        var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next().getValue();
            if (slot.owner == null && slot.pending.isEmpty()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    synchronized Set<String> accepted(Scope scope, String taskId) {
        Slot slot = slots.get(new TaskKey(scope, taskId));
        return slot == null ? Set.of() : Set.copyOf(slot.accepted);
    }

    /**
     * Rebuild only an idle, server-validated waiting task. Never replace a concurrent owner.
     *
     * @param scope authenticated conversation scope
     * @param taskId validated waiting task identity
     * @param accepted previously accepted message identities
     * @return whether the waiting index exists or was restored
     */
    synchronized boolean restore(Scope scope, String taskId, Set<String> accepted) {
        TaskKey key = new TaskKey(scope, taskId);
        if (isClosed || accepted.size() > maxInputs) {
            return false;
        }
        if (slots.containsKey(key)) {
            return true;
        }
        if (!makeRoom()) {
            return false;
        }
        Slot slot = new Slot(taskId);
        slot.accepted.addAll(accepted);
        slots.put(key, slot);
        return true;
    }

    synchronized void cancelTask(String taskId) {
        var iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().taskId().equals(taskId)) {
                Slot slot = entry.getValue();
                slot.isCancelled = true;
                slot.pending.clear();
                if (slot.owner == null) {
                    iterator.remove();
                }
            }
        }
        notifyAll();
    }

    /**
     * A client may observe INPUT_REQUIRED before the previous producer finishes cleanup.
     *
     * @param scope authenticated conversation scope
     * @param taskId waiting task identity
     * @return ownership after cleanup, or empty when unavailable or interrupted
     */
    synchronized Optional<Lease> resume(Scope scope, String taskId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        TaskKey key = new TaskKey(scope, taskId);
        while (!isClosed) {
            Slot slot = slots.get(key);
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
                // Preserve cancellation for the execution owner; waiting must not consume it.
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Stores input only for a bound task. Repeated message IDs never execute twice.
     *
     * @param scope authenticated conversation scope
     * @param taskId bound task identity
     * @param messageId deduplication identity
     * @param input request to deliver serially
     * @return enqueue result
     */
    synchronized Offer offer(Scope scope, String taskId, String messageId, ServeRequest input) {
        Objects.requireNonNull(scope, "scope");
        requireIdentity(taskId, "taskId");
        requireIdentity(messageId, "messageId");
        Objects.requireNonNull(input, "input");
        if (isClosed) {
            return Offer.CLOSED;
        }
        Slot slot = slots.get(new TaskKey(scope, taskId));
        if (slot == null || slot.isCancelled || !slot.taskId.equals(taskId)) {
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

    /**
     * Claims pending input for an idle task; caller owns dispatch and output handling.
     *
     * @param scope authenticated conversation scope
     * @param taskId bound task identity
     * @return next input with exclusive ownership, or empty
     */
    synchronized Optional<Lease> claim(Scope scope, String taskId) {
        Slot slot = slots.get(new TaskKey(scope, taskId));
        if (isClosed || slot == null || !slot.taskId.equals(taskId) || slot.owner != null) {
            return Optional.empty();
        }
        return next(new TaskKey(scope, taskId), slot);
    }

    /**
     * Transfers to queued input while retaining exclusive ownership when no input exists.
     *
     * @param lease current round ownership
     * @return next owned input, or empty without releasing the current owner
     */
    synchronized Optional<Lease> advance(Lease lease) {
        Slot slot = slots.get(lease.key);
        if (isClosed || slot == null || slot.owner != lease || slot.pending.isEmpty()) {
            return Optional.empty();
        }
        return next(lease.key, slot);
    }

    /**
     * Releases the owner and atomically hands off pending input, or discards it on termination.
     *
     * @param lease current round ownership
     * @param isTerminal whether the task has terminated
     * @return ownership of pending input, or empty
     */
    synchronized Optional<Lease> finish(Lease lease, boolean isTerminal) {
        Objects.requireNonNull(lease, "lease");
        Slot slot = slots.get(lease.key);
        if (isClosed || slot == null || slot.owner != lease) {
            return Optional.empty();
        }
        notifyAll();
        if (isTerminal || slot.isCancelled) {
            slots.remove(lease.key);
            return Optional.empty();
        }
        slot.owner = null;
        return next(lease.key, slot);
    }

    synchronized boolean release(Lease lease, boolean isTerminal) {
        Slot slot = slots.get(lease.key);
        if (isClosed || slot == null || slot.owner != lease) {
            return false;
        }
        notifyAll();
        if (isTerminal || slot.isCancelled) {
            slots.remove(lease.key);
            return false;
        }
        slot.owner = null;
        return !slot.pending.isEmpty();
    }

    /**
     * Returns unexecuted input to the head when admission rejects its claimed round.
     *
     * @param lease rejected round ownership
     */
    synchronized void retry(Lease lease) {
        Slot slot = slots.get(lease.key);
        if (!isClosed && slot != null && slot.owner == lease && lease.input != null) {
            slot.pending.addFirst(lease.input);
            slot.owner = null;
        }
    }

    private Optional<Lease> next(TaskKey key, Slot slot) {
        ServeRequest input = slot.pending.pollFirst();
        if (input == null) {
            return Optional.empty();
        }
        slot.owner = new Lease(key, input);
        return Optional.of(slot.owner);
    }

    @Override
    public synchronized void close() {
        isClosed = true;
        slots.clear();
        notifyAll();
    }

    private static void requireIdentity(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class Slot {
        private final String taskId;
        private final ArrayDeque<ServeRequest> pending = new ArrayDeque<>();
        private final Set<String> accepted = new HashSet<>();
        private Lease owner;
        private boolean isCancelled;

        private Slot(String taskId) {
            this.taskId = taskId;
        }
    }
}
