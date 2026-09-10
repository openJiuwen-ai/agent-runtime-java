/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.app.controller.a2a.A2AAgentExecutor;
import com.openjiuwen.service.app.controller.a2a.A2ATaskContinuation;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackStore;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed dependencies of one hosted target. This is an assembly result, not a
 * request context or a second execution framework.
 *
 * @since 0.1.2
 */
public final class HostedAgentRuntime {
    private final String agentId;

    private final AgentHandler handler;

    private final Execution execution;

    private final Map<Class<?>, Object> extensions;

    private final List<Runnable> cleanup;

    HostedAgentRuntime(String agentId, AgentHandler handler, Execution execution,
            Map<Class<?>, Object> extensions, List<Runnable> cleanup) {
        this.agentId = agentId;
        this.handler = handler;
        this.execution = execution;
        this.extensions = Map.copyOf(extensions);
        this.cleanup = List.copyOf(cleanup);
    }

    public String agentId() {
        return agentId;
    }

    public AgentHandler handler() {
        return handler;
    }

    /**
     * Returns this target's request orchestrator.
     *
     * @return target-local orchestrator
     */
    public A2AEnabledServeOrchestrator orchestrator() {
        return execution.orchestrator();
    }

    /**
     * Returns this target's SDK request handler.
     *
     * @return target-local SDK handler
     */
    public RequestHandler requestHandler() {
        return execution.requestHandler();
    }

    /**
     * Returns this target's task storage view.
     *
     * @return target-local task store
     */
    public TaskStore taskStore() {
        return execution.taskStore();
    }

    /**
     * Returns the active streams owned by this target.
     *
     * @return target-local stream registry
     */
    public ActiveStreamRegistry streams() {
        return execution.streams();
    }

    /**
     * Returns the SDK graph for framework assembly and lifecycle consumers.
     *
     * @return target-local execution graph
     */
    public Execution execution() {
        return execution;
    }

    /**
     * Returns an optional module's already assembled target-local dependency.
     *
     * @param type framework integration dependency type
     * @param <T> dependency type
     * @return dependency, absent when the module is disabled
     */
    public <T> Optional<T> extension(Class<T> type) {
        return Optional.ofNullable(type.cast(extensions.get(type)));
    }

    List<Runnable> cleanup() {
        return cleanup;
    }

    /**
     * SDK and orchestration references bound before publication.
     */
    public record Execution(A2AEnabledServeOrchestrator orchestrator, RequestHandler requestHandler,
            TaskStore taskStore, ActiveStreamRegistry streams, A2AAgentExecutor agentExecutor,
            A2ATaskContinuation continuation, MainEventBus eventBus, MainEventBusProcessor eventProcessor,
            QueueManager queueManager, PushNotificationConfigStore pushConfigStore,
            PushNotificationSender pushSender, A2aPushNotificationCallbackStore callbackStore) {
    }
}
