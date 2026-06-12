/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Bridges Spring lifecycle events to {@link AgentLifecycleManager}.
 */
public class AgentLifecycleBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleBootstrap.class);

    private final AgentLifecycleManager lifecycleManager;

    public AgentLifecycleBootstrap(AgentLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        lifecycleManager.runInitPhase();
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        log.debug("Context closed, running Agent shutdown phase");
        lifecycleManager.runShutdownPhase();
    }
}
