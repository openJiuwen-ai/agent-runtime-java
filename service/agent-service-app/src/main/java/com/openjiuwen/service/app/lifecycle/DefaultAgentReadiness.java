/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

/**
 * Default {@link AgentReadiness} backed by lifecycle state.
 */
public class DefaultAgentReadiness implements AgentReadiness {

    private volatile boolean processUp = true;
    private volatile boolean agentLoaded = false;
    private volatile boolean shuttingDown = false;

    @Override
    public boolean isProcessUp() {
        return processUp && !shuttingDown;
    }

    @Override
    public boolean isAgentLoaded() {
        return agentLoaded && !shuttingDown;
    }

    public void markAgentLoaded(boolean loaded) {
        this.agentLoaded = loaded;
    }

    public void markShuttingDown() {
        shuttingDown = true;
        agentLoaded = false;
    }

    public void markProcessDown() {
        processUp = false;
        agentLoaded = false;
        shuttingDown = true;
    }
}
