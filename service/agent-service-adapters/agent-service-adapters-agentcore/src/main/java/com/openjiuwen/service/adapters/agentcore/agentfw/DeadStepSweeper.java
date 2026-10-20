/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.step.StepCheckpointRecoveryService;
import com.openjiuwen.core.session.checkpointer.step.StepRecoveryHook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-side dead-step sweeper.
 *
 * <p>Runs a periodic scan over the checkpointer's RUNNING step cursors and
 * walks the shared recovery pipeline ({@link StepCheckpointRecoveryService}):
 * hook first, markLost when the hook does not recover. A per-process atomic
 * guard prevents overlapping scans; multi-instance dedup relies on the
 * recovery service's processed registry plus the callers' own state - a
 * distributed lock can be layered later without changing this contract.</p>
 *
 * <p>Lifecycle: the auto-configuration registers this bean with
 * {@code destroyMethod = "stop"} so Spring stops the scheduler at context
 * shutdown. Enabled explicitly via the {@code openjiuwen.step-sweeper.enabled}
 * property (default off); the sweep interval is configurable.</p>
 *
 * @since 0.1.4
 */
public class DeadStepSweeper {
    private static final Logger log = LoggerFactory.getLogger(DeadStepSweeper.class);

    private final Checkpointer checkpointer;
    private final StepRecoveryHook hook;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isSweeping = new AtomicBoolean(false);
    private volatile StepCheckpointRecoveryService recoveryService;

    /**
     * Create a sweeper.
     *
     * @param checkpointer checkpointer providing the RUNNING cursor scan
     * @param hook business recovery hook, may be null
     * @param intervalSeconds sweep interval in seconds, positive values only
     */
    public DeadStepSweeper(Checkpointer checkpointer, StepRecoveryHook hook, long intervalSeconds) {
        this.checkpointer = checkpointer;
        this.hook = hook;
        this.intervalSeconds = Math.max(1L, intervalSeconds);
        Thread.UncaughtExceptionHandler uncaughtHandler = (threadRef, throwable) ->
                log.error("DeadStepSweeper thread {} died unexpectedly", threadRef.getName(), throwable);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                new CustomizableThreadFactory("dead-step-sweeper-") {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = super.newThread(runnable);
                        thread.setUncaughtExceptionHandler(uncaughtHandler);
                        return thread;
                    }
                });
        executor.setRemoveOnCancelPolicy(true);
        this.scheduler = executor;
    }

    /**
     * Start the periodic sweep.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::sweepOnce,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("DeadStepSweeper started with interval {}s", intervalSeconds);
    }

    /**
     * Run exactly one sweep pass; also used by tests and manual triggers.
     *
     * @return actions taken during the pass, empty when a sweep is already running
     */
    public List<StepCheckpointRecoveryService.RecoveryAction> sweepOnce() {
        if (!isSweeping.compareAndSet(false, true)) {
            return List.of();
        }
        try {
            StepCheckpointRecoveryService service = recoveryService;
            if (service == null) {
                service = new StepCheckpointRecoveryService(checkpointer, hook);
                recoveryService = service;
            }
            List<StepCheckpointRecoveryService.RecoveryAction> actions = service.recoverLeftovers();
            if (!actions.isEmpty()) {
                log.info("Dead-step sweep processed {} leftover session(s)", actions.size());
            }
            return actions;
        } finally {
            isSweeping.set(false);
        }
    }

    /**
     * Stop the sweeper and release its thread; invoked by Spring through the
     * bean definition's destroy method.
     */
    public void stop() {
        scheduler.shutdownNow();
        log.info("DeadStepSweeper stopped");
    }
}
