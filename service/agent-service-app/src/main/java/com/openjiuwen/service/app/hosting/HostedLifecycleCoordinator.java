/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration.HostedResources;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.HostedSharedLifecycle;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Owns atomic startup and shutdown of all targets, followed by borrowed shared
 * resources. A stop callback returning does not imply strict business-thread drain.
 *
 * @since 0.1.2
 */
public final class HostedLifecycleCoordinator implements AgentLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(HostedLifecycleCoordinator.class);

    private final Configuration configuration;

    private final HostedRuntimeAssembler assembler;

    private final HostedRuntimeCatalog catalog;

    private final List<HostedAgentRuntime> instances = new ArrayList<>();

    private final List<HostedSharedLifecycle> prepared = new ArrayList<>();

    private final List<HostedAgentRuntime> started = new ArrayList<>();

    private final List<Future<?>> processors = new ArrayList<>();

    private boolean isInitialized;

    private boolean isStopped;

    private boolean hasStartedHooks;

    public HostedLifecycleCoordinator(Configuration configuration, HostedRuntimeAssembler assembler,
            HostedRuntimeCatalog catalog) {
        this.configuration = configuration;
        this.assembler = assembler;
        this.catalog = catalog;
    }

    @Override
    public synchronized void runInitPhase() {
        if (isInitialized || isStopped) {
            return;
        }
        String applicationName = configuration.identity().getAppName();
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("Hosted runtime requires a stable application identity");
        }
        try {
            for (HostedSharedLifecycle lifecycle : configuration.shared()) {
                prepared.add(lifecycle);
                lifecycle.prepare(applicationName, configuration.definitions());
            }
            for (var entry : configuration.definitions().entries()) {
                instances.add(assembler.assemble(applicationName, entry));
            }
            for (HostedSharedLifecycle lifecycle : prepared) {
                lifecycle.start();
            }
            AgentLifecycleContext context = new AgentLifecycleContext(applicationName);
            hasStartedHooks = true;
            for (var hook : configuration.hooks().initHooks()) {
                hook.onInit(context);
            }
            for (HostedAgentRuntime instance : instances) {
                processors.add(configuration.resources().startProcessor(instance.execution().eventProcessor()));
                started.add(instance);
                startHandler(instance);
            }
            catalog.publish(instances);
            configuration.readiness().markAgentLoaded(true);
            isInitialized = true;
        } catch (Exception | Error failure) {
            shutdown(true);
            throw new IllegalStateException("Hosted runtime startup failed", failure);
        }
    }

    private static void startHandler(HostedAgentRuntime instance) {
        log.info("Hosted agent agentId={} operation=start result=begin", instance.agentId());
        try {
            instance.handler().start();
            log.info("Hosted agent agentId={} operation=start result=success", instance.agentId());
        } catch (RuntimeException | Error failure) {
            log.error("Hosted agent agentId={} operation=start result=failure type={}", instance.agentId(),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    @Override
    public synchronized void runShutdownPhase() {
        shutdown(false);
    }

    private void shutdown(boolean isRollback) {
        if (isStopped) {
            return;
        }
        isStopped = true;
        catalog.close();
        configuration.readiness().markShuttingDown();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0, configuration.properties().getShutdownTimeoutMs()));
        for (HostedAgentRuntime instance : instances) {
            stopAction(instance.agentId(), "retry", instance.execution().continuation()::shutdown);
            stopAction(instance.agentId(), "dispatch", instance.orchestrator()::stopDispatching);
            stopAction(instance.agentId(), "cancel", instance.streams()::cancelAll);
        }
        for (HostedAgentRuntime instance : instances) {
            long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (!instance.streams().awaitDrain(remaining)) {
                log.warn("Hosted shutdown wait expired agentId={} remainingStreams={}", instance.agentId(),
                        instance.streams().activeCount());
            }
        }
        if (hasStartedHooks) {
            runShutdownHooks();
        }
        for (int i = started.size() - 1; i >= 0; i--) {
            stopHandler(started.get(i), isRollback);
        }
        processors.forEach(future -> stopAction("process", "event-processor", () -> future.cancel(true)));
        for (int i = instances.size() - 1; i >= 0; i--) {
            HostedRuntimeAssembler.cleanup(instances.get(i).cleanup());
        }
        for (int i = prepared.size() - 1; i >= 0; i--) {
            try {
                long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                prepared.get(i).stop(remaining);
            } catch (RuntimeException exception) {
                log.error("Hosted shared resource stop failed type={}", exception.getClass().getSimpleName());
            }
        }
        configuration.resources().close();
        configuration.readiness().markProcessDown();
    }

    private static void stopAction(String agentId, String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("Hosted shutdown failed agentId={} operation={} type={}", agentId, operation,
                    exception.getClass().getSimpleName());
        }
    }

    private void runShutdownHooks() {
        var context = new AgentLifecycleContext(configuration.identity().getAppName());
        var hooks = configuration.hooks().shutdownHooks();
        for (int i = hooks.size() - 1; i >= 0; i--) {
            try {
                hooks.get(i).onShutdown(context);
            } catch (Exception exception) {
                log.error("Hosted shutdown hook failed type={}", exception.getClass().getSimpleName());
            }
        }
    }

    private static void stopHandler(HostedAgentRuntime instance, boolean isRollback) {
        log.info("Hosted agent agentId={} operation=stop result=begin rollback={}",
                instance.agentId(), isRollback);
        try {
            instance.handler().stop();
            log.info("Hosted agent agentId={} operation=stop result=success rollback={}",
                    instance.agentId(), isRollback);
        } catch (RuntimeException exception) {
            log.error("Hosted agent agentId={} operation=stop result=failure rollback={} type={}", instance.agentId(),
                    isRollback, exception.getClass().getSimpleName());
        }
    }

    @Override
    public void interrupt(String conversationId) {
        configuration.interruptor().interrupt(conversationId);
    }

    /**
     * Existing hooks and policies plus explicitly owned shared resources.
     */
    public record Configuration(HostedAgentDefinitions definitions, AgentServiceIdentity identity,
            AgentLifecycleHooks hooks, DefaultAgentReadiness readiness, LifecycleProperties properties,
            List<HostedSharedLifecycle> shared, HostedResources resources, ActiveStreamInterruptor interruptor) {
        public Configuration {
            shared = List.copyOf(shared);
        }
    }
}
