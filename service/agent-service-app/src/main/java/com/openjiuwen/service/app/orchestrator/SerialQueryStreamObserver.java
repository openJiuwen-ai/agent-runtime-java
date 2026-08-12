/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.ArrayDeque;
import java.util.Deque;

/** Serializes callbacks from concurrent remote futures without owning an executor. */
final class SerialQueryStreamObserver implements QueryStreamObserver {
    private final QueryStreamObserver delegate;

    private final Deque<QueryChunk> pending = new ArrayDeque<>();

    private boolean isDraining;

    SerialQueryStreamObserver(QueryStreamObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onNext(QueryChunk chunk) {
        boolean isDrainOwner = false;
        synchronized (pending) {
            pending.addLast(chunk);
            if (!isDraining) {
                isDraining = true;
                isDrainOwner = true;
            }
        }
        if (!isDrainOwner) {
            return;
        }
        while (true) {
            QueryChunk next;
            synchronized (pending) {
                next = pending.pollFirst();
                if (next == null) {
                    isDraining = false;
                    pending.notifyAll();
                    return;
                }
            }
            try {
                delegate.onNext(next);
            } catch (RuntimeException | Error ex) {
                synchronized (pending) {
                    pending.clear();
                    isDraining = false;
                    pending.notifyAll();
                }
                throw ex;
            }
        }
    }

    void awaitDrained() {
        synchronized (pending) {
            while (isDraining) {
                try {
                    pending.wait();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException("Interrupted while waiting for remote stream events", ex);
                }
            }
        }
    }

    @Override
    public void onComplete() {
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }
}
