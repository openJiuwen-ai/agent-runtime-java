/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drains active streams and runs {@link AgentShutdownHook}s in reverse order.
 */
public final class ShutdownPhaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShutdownPhaseExecutor.class);

    private final AgentServiceIdentity identity;
    private final AgentLifecycleHooks hooks;
    private final DefaultAgentReadiness readiness;
    private final ActiveStreamRegistry streamRegistry;
    private final ObjectProvider<AgentHandler> agentHandlerProvider;
    private final LifecycleProperties properties;

    public ShutdownPhaseExecutor(AgentServiceIdentity identity, AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness, ActiveStreamRegistry streamRegistry,
            ObjectProvider<AgentHandler> agentHandlerProvider, LifecycleProperties properties) {
        this.identity = identity;
        this.hooks = hooks;
        this.readiness = readiness;
        this.streamRegistry = streamRegistry;
        this.agentHandlerProvider = agentHandlerProvider;
        this.properties = properties;
    }

    public void run() {
        String appName = identity.getAppName();
        log.info("Starting Agent shutdown phase for application '{}', activeStreams={}", appName,
                streamRegistry.activeCount());
        readiness.markShuttingDown();
        drainActiveStreams();
        AgentLifecycleContext context = new AgentLifecycleContext(appName);
        List<AgentShutdownHook> shutdownHooks = new ArrayList<>(hooks.shutdownHooks());
        Collections.reverse(shutdownHooks);
        for (AgentShutdownHook hook : shutdownHooks) {
            try {
                log.debug("Running AgentShutdownHook: {}", hook.getClass().getName());
                hook.onShutdown(context);
            } catch (Exception ex) {
                log.warn("AgentShutdownHook failed: {}", hook.getClass().getName(), ex);
            }
        }
        AgentHandler handler = agentHandlerProvider.getIfAvailable();
        if (handler != null) {
            handler.stop();
        }
        readiness.markProcessDown();
        log.info("Agent shutdown phase completed for application '{}'", appName);
    }

    private void drainActiveStreams() {
        streamRegistry.cancelAll();
        boolean drained = streamRegistry.awaitDrain(properties.getShutdownTimeoutMs());
        if (!drained) {
            log.warn("Active streams not drained within {} ms, forcing cancel (remaining={})",
                    properties.getShutdownTimeoutMs(), streamRegistry.activeCount());
            streamRegistry.cancelAll();
            streamRegistry.awaitDrain(1000L);
        }
    }
}
