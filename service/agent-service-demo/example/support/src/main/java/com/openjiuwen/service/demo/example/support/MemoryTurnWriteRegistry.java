/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Records memory writes made by the memory tools within one conversation turn.
 *
 * <p>The demo exposes two write paths: the {@code memory_add} tool invoked by the model, and the
 * lifecycle {@code syncTurn} writeback that runs after the turn. When the tool already persisted
 * a fact, the writeback extracts the same fact again: a second extraction/dedup round that costs
 * latency and can leave a duplicate unit, because the tool writes a single fact while the
 * writeback writes the whole turn (so their embeddings are not close enough to be merged).
 *
 * <p>The tool announces its write here and the lifecycle handler consumes the announcement, so a
 * turn is written through exactly one path. Announcements are keyed by conversation id, expire
 * after {@link #PENDING_TTL}, and the map is capped, so a turn that never completes cannot grow
 * the registry without bound.
 *
 * @since 0.1.0
 */
public final class MemoryTurnWriteRegistry {
    private static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private static final int MAX_PENDING = 1024;

    private final Map<String, Long> pendingToolWrites = new ConcurrentHashMap<>();

    private final LongSupplier nanoClock;

    /**
     * Creates a registry backed by the system monotonic clock.
     */
    public MemoryTurnWriteRegistry() {
        this(System::nanoTime);
    }

    /**
     * Creates a registry with an injectable clock, for tests.
     *
     * @param nanoClock monotonic clock in nanoseconds
     */
    MemoryTurnWriteRegistry(LongSupplier nanoClock) {
        this.nanoClock = nanoClock != null ? nanoClock : System::nanoTime;
    }

    /**
     * Announces that the memory tool stored a fact in the given conversation.
     *
     * @param conversationId conversation (session) id, ignored when blank
     */
    public void markToolWrite(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        long now = nanoClock.getAsLong();
        evictExpired(now);
        if (pendingToolWrites.size() >= MAX_PENDING) {
            pendingToolWrites.clear();
        }
        pendingToolWrites.put(conversationId, now);
    }

    /**
     * Consumes the announcement for the given conversation.
     *
     * @param conversationId conversation (session) id
     * @return true when the tool already wrote in this turn (and the announcement is removed)
     */
    public boolean consumeToolWrite(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        Long markedAt = pendingToolWrites.remove(conversationId);
        return markedAt != null && nanoClock.getAsLong() - markedAt <= PENDING_TTL.toNanos();
    }

    private void evictExpired(long now) {
        long ttl = PENDING_TTL.toNanos();
        pendingToolWrites.entrySet().removeIf(entry -> now - entry.getValue() > ttl);
    }
}
