/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervised variant of the SDK's {@link MainEventBusProcessor}.
 * <p>
 * The SDK's processing loop catches {@link Exception} only, so a single
 * {@link Error} (e.g. {@code OutOfMemoryError} while the heap is under
 * pressure) permanently kills the one and only event consumer thread. From that
 * moment on, no event is ever persisted or distributed to client streams, which
 * turns every subsequent request into an empty response until the process is
 * restarted.
 * <p>
 * This subclass keeps the SDK loop as-is but wraps it in a supervisor: when the
 * loop dies with a {@link Throwable}, it is restarted after a fixed backoff.
 * The loop returns normally only on graceful shutdown ({@code stop()} or an
 * interrupt), in which case the supervisor exits too. The event bus backlog is
 * not cleared on restart, so pending events are picked up where the dead loop
 * left off.
 *
 * @since 0.1.2
 */
public class ResilientMainEventBusProcessor extends MainEventBusProcessor {
    private static final Logger log = LoggerFactory.getLogger(ResilientMainEventBusProcessor.class);

    private static final long DEFAULT_RESTART_BACKOFF_MS = 5_000L;

    private final long restartBackoffMs;

    private final AtomicInteger restarts = new AtomicInteger();

    /**
     * Creates a supervised processor with the default restart backoff.
     *
     * @param eventBus the main event bus to consume from
     * @param taskStore the task store used for persistence
     * @param pushSender the push notification sender
     * @param queueManager the queue manager owning the per-task queues
     */
    public ResilientMainEventBusProcessor(MainEventBus eventBus, TaskStore taskStore,
            PushNotificationSender pushSender, QueueManager queueManager) {
        this(eventBus, taskStore, pushSender, queueManager, DEFAULT_RESTART_BACKOFF_MS);
    }

    ResilientMainEventBusProcessor(MainEventBus eventBus, TaskStore taskStore,
            PushNotificationSender pushSender, QueueManager queueManager, long restartBackoffMs) {
        super(eventBus, taskStore, pushSender, queueManager);
        this.restartBackoffMs = restartBackoffMs;
    }

    /**
     * Returns the number of times the processing loop died and was restarted.
     *
     * @return the restart count since bean creation
     */
    public int getRestartCount() {
        return restarts.get();
    }

    @Override
    public void run() {
        while (true) {
            try {
                // Returns only on graceful shutdown (stop() or interrupt); any
                // Throwable means the loop died and must be restarted.
                super.run();
                return;
            } catch (Throwable t) {
                log.error("A2A main event bus processing loop died; restarting after {}ms (restart #{})",
                        restartBackoffMs, restarts.incrementAndGet(), t);
            }
            try {
                Thread.sleep(restartBackoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
