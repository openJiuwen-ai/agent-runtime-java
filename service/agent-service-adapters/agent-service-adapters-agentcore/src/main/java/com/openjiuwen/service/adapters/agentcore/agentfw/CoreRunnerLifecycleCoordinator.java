/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.HostedSharedLifecycle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the Core process Runner once for all registered Core handlers. Does not
 * inspect or rewrite session IDs, Core IDs, checkpoint keys, or business requests.
 *
 * @since 0.1.2
 */
public final class CoreRunnerLifecycleCoordinator implements HostedSharedLifecycle {
    private final MiddlewareAdapterRegistrar middleware;

    private final ExternalSvcAdapterRegistrar external;

    private boolean hasCoreHandlers;

    private boolean isPrepared;

    private boolean isStarted;

    private boolean isStopped;

    public CoreRunnerLifecycleCoordinator(MiddlewareAdapterRegistrar middleware, ExternalSvcAdapterRegistrar external) {
        this.middleware = middleware;
        this.external = external;
    }

    @Override
    public synchronized void prepare(String applicationName, HostedAgentDefinitions definitions) {
        if (isPrepared || isStopped) {
            throw new IllegalStateException("Shared Core Runner has already been prepared or stopped");
        }
        List<JiuwenCoreAgentHandler> handlers = new ArrayList<>();
        Map<Object, String> agents = new IdentityHashMap<>();
        Set<String> references = new HashSet<>();
        for (var entry : definitions.entries()) {
            if (entry.handler() instanceof JiuwenCoreAgentHandler handler) {
                handler.validateHostedRunner(middleware, external);
                Object agent = handler.getAgent();
                if (agent == null) {
                    throw new IllegalStateException("Hosted Core handler has no agent: " + entry.agentId());
                }
                boolean isDuplicate = agent instanceof String reference ? !references.add(reference)
                        : agents.put(agent, entry.agentId()) != null;
                if (isDuplicate) {
                    throw new IllegalStateException("Core agent object is hosted more than once: " + entry.agentId());
                }
                handlers.add(handler);
            }
        }
        for (JiuwenCoreAgentHandler handler : handlers) {
            handler.bindHostedRunner(middleware, external);
        }
        hasCoreHandlers = !handlers.isEmpty();
        isPrepared = true;
    }

    @Override
    public synchronized void start() {
        if (!isPrepared || isStopped) {
            throw new IllegalStateException("Shared Core Runner must be prepared before startup");
        }
        if (!hasCoreHandlers || isStarted) {
            return;
        }
        // Mark ownership before side effects so partial startup can be rolled back.
        isStarted = true;
        if (middleware != null) {
            middleware.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        }
        if (external != null) {
            external.registerToRunner();
        }
        Runner.start();
    }

    @Override
    public synchronized void stop() {
        if (isStopped) {
            return;
        }
        isStopped = true;
        if (isStarted) {
            Runner.stop();
        }
    }
}
