/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.a2aproject.sdk.server.events.FlakyMainEventBus;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the supervisor semantics of {@link ResilientMainEventBusProcessor}:
 * the processing loop is restarted after dying with an {@link Error}, a
 * graceful shutdown exits the supervisor, and an interrupt during the restart
 * backoff is honored instead of blocking shutdown.
 */
class ResilientMainEventBusProcessorTest {
    @Test
    void restartsLoopAfterErrorAndExitsGracefullyOnShutdown() {
        FlakyMainEventBus bus = new FlakyMainEventBus();
        bus.script(new AssertionError("first death"));
        bus.script(new OutOfMemoryError("second death"));
        ResilientMainEventBusProcessor processor = new ResilientMainEventBusProcessor(bus, mock(TaskStore.class),
                mock(PushNotificationSender.class), mock(QueueManager.class), 0L);

        processor.run();

        // Two deaths were survived and the third take() ended the loop gracefully.
        assertThat(processor.getRestartCount()).isEqualTo(2);
        assertThat(bus.takeCalls()).isEqualTo(3);
    }

    @Test
    void backoffInterruptEndsSupervisor() throws Exception {
        FlakyMainEventBus bus = new FlakyMainEventBus();
        bus.script(new AssertionError("death"));
        ResilientMainEventBusProcessor processor = new ResilientMainEventBusProcessor(bus, mock(TaskStore.class),
                mock(PushNotificationSender.class), mock(QueueManager.class), 60_000L);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            Future<?> supervisor = executor.submit(processor);
            long deadline = System.currentTimeMillis() + 5_000L;
            while (processor.getRestartCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(processor.getRestartCount()).isEqualTo(1);

            // Cancelling with mayInterruptIfRunning delivers the interrupt to the
            // backoff sleep, which must terminate the supervisor promptly.
            supervisor.cancel(true);
            executor.shutdown();
            assertThat(executor.awaitTermination(5_000L, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
