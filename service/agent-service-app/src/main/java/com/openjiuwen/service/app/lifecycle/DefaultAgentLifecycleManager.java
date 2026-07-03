/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin lifecycle coordinator: delegates init, shutdown and interrupt to
 * dedicated executors.
 */
public class DefaultAgentLifecycleManager implements AgentLifecycleManager {

    private final InitPhaseExecutor initPhaseExecutor;
    private final ShutdownPhaseExecutor shutdownPhaseExecutor;
    private final ActiveStreamInterruptor streamInterruptor;
    private final AtomicBoolean initCompleted = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public DefaultAgentLifecycleManager(InitPhaseExecutor initPhaseExecutor,
            ShutdownPhaseExecutor shutdownPhaseExecutor, ActiveStreamInterruptor streamInterruptor) {
        this.initPhaseExecutor = initPhaseExecutor;
        this.shutdownPhaseExecutor = shutdownPhaseExecutor;
        this.streamInterruptor = streamInterruptor;
    }

    @Override
    public void runInitPhase() {
        if (!initCompleted.compareAndSet(false, true)) {
            return;
        }
        initPhaseExecutor.run();
    }

    @Override
    public void runShutdownPhase() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        shutdownPhaseExecutor.run();
    }

    @Override
    public void interrupt(String conversationId) {
        streamInterruptor.interrupt(conversationId);
    }
}
