/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks active streaming queries per {@code conversation_id} for interrupt and shutdown drain.
 */
public class ActiveStreamRegistry {

    private final ConcurrentHashMap<String, Set<StreamCancellationHandle>> active =
            new ConcurrentHashMap<>();

    public StreamCancellationHandle register(String conversationId) {
        StreamCancellationHandle handle = new StreamCancellationHandle(conversationId);
        active.computeIfAbsent(conversationId, id -> ConcurrentHashMap.newKeySet()).add(handle);
        return handle;
    }

    public void unregister(String conversationId, StreamCancellationHandle handle) {
        Set<StreamCancellationHandle> handles = active.get(conversationId);
        if (handles == null) {
            return;
        }
        handles.remove(handle);
        if (handles.isEmpty()) {
            active.remove(conversationId, handles);
        }
    }

    public void cancel(String conversationId) {
        Set<StreamCancellationHandle> handles = active.remove(conversationId);
        if (handles != null) {
            for (StreamCancellationHandle handle : handles) {
                handle.cancel();
            }
        }
    }

    public void cancelAll() {
        for (String conversationId : active.keySet()) {
            cancel(conversationId);
        }
    }

    public int activeCount() {
        int count = 0;
        for (Set<StreamCancellationHandle> handles : active.values()) {
            count += handles.size();
        }
        return count;
    }

    /**
     * Waits until no active streams remain or timeout elapses.
     *
     * @return true if drained, false if timeout
     */
    public boolean awaitDrain(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (activeCount() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }
}
