/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package org.a2aproject.sdk.server.events;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test-only {@link MainEventBus} whose {@link #take()} throws a scripted
 * sequence of throwables instead of delivering events. Living in the SDK's
 * package allows overriding the package-private {@code take()}. Once the script
 * is exhausted, {@code take()} throws {@link InterruptedException}, which the
 * processing loop treats as graceful shutdown.
 */
public class FlakyMainEventBus extends MainEventBus {

    private final Queue<Throwable> script = new ConcurrentLinkedQueue<>();

    private volatile int takeCalls;

    /**
     * Appends a failure to be thrown by the next unconsumed {@link #take()}
     * call, in order.
     *
     * @param failure the throwable to throw
     */
    public void script(Throwable failure) {
        script.add(failure);
    }

    /**
     * Returns how many times {@link #take()} has been called.
     *
     * @return the take call count
     */
    public int takeCalls() {
        return takeCalls;
    }

    @Override
    MainEventBusContext take() throws InterruptedException {
        takeCalls++;
        Throwable failure = script.poll();
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new InterruptedException("end of script");
    }
}
