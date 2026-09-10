/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration.HostedResources;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.A2AAgentExecutor;
import com.openjiuwen.service.app.controller.a2a.A2AProtocolAdapter;
import com.openjiuwen.service.app.controller.a2a.A2ATaskContinuation;
import com.openjiuwen.service.app.controller.a2a.HttpPushNotificationSender;
import com.openjiuwen.service.app.controller.a2a.InMemoryA2aPushNotificationCallbackStore;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationDispatcher;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.ScopedRuntimeRedisClient;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parameterizes the existing execution graph once per registration. Extensions
 * bind only framework-owned dependencies and never recreate user handlers.
 *
 * @since 0.1.2
 */
public final class HostedRuntimeAssembler {
    private static final Logger log = LoggerFactory.getLogger(HostedRuntimeAssembler.class);

    private final Dependencies dependencies;

    private final List<Extension> extensions;

    public HostedRuntimeAssembler(Dependencies dependencies, List<Extension> extensions) {
        this.dependencies = dependencies;
        this.extensions = List.copyOf(extensions);
    }

    HostedAgentRuntime assemble(String applicationName, HostedAgentDefinitions.Entry entry) {
        RuntimeRedisClient scoped = dependencies.redisClient() == null ? null
                : new ScopedRuntimeRedisClient(dependencies.redisClient(),
                        ScopedRuntimeRedisClient.namespace(applicationName, entry.agentId()));
        Assembly assembly = new Assembly(applicationName, entry, scoped);
        try {
            TaskStore store = A2AAutoConfiguration.createTaskStore(dependencies.middleware(), scoped,
                    dependencies.properties());
            assembly.taskStateProvider = store instanceof TaskStateProvider provider ? provider : null;
            assembly.taskStore = store;
            for (Extension extension : extensions) {
                store = Objects.requireNonNull(extension.decorateTaskStore(assembly, store), "Decorated TaskStore");
                assembly.taskStore = store;
            }
            buildExecution(assembly);
            for (Extension extension : extensions) {
                extension.configureHandler(assembly);
            }
            return new HostedAgentRuntime(entry.agentId(), entry.handler(), assembly.execution,
                    assembly.components, assembly.cleanup);
        } catch (RuntimeException | Error failure) {
            cleanup(assembly.cleanup);
            throw failure;
        }
    }

    private void buildExecution(Assembly assembly) {
        TaskStore store = assembly.taskStore;
        var bus = new MainEventBus();
        TaskStateProvider provider = store instanceof TaskStateProvider stateProvider
                ? stateProvider : assembly.taskStateProvider;
        var queues = new InMemoryQueueManager(provider, bus);
        var pushConfigs = new InMemoryPushNotificationConfigStore();
        var sender = new HttpPushNotificationSender(pushConfigs, dependencies.httpClient());
        AtomicReference<A2AAgentExecutor> localExecutor = new AtomicReference<>();
        ObjectProvider<A2AAgentExecutor> executorProvider = new ObjectProvider<>() {
            @Override
            public A2AAgentExecutor getObject() {
                return Objects.requireNonNull(localExecutor.get(), "Local A2A executor is not bound");
            }
        };
        var continuation = new A2ATaskContinuation(store, queues, executorProvider,
                dependencies.resources().agentExecutor(), dependencies.resources().retryScheduler());
        assembly.onClose(continuation::shutdown);
        RemoteAgentCaller caller = boundCaller(assembly.agentId());
        for (Extension extension : extensions) {
            caller = Objects.requireNonNull(extension.decorateRemoteCaller(assembly, caller), "RemoteAgentCaller");
        }
        var streams = new ActiveStreamRegistry();
        var orchestrator = new A2AEnabledServeOrchestrator(assembly.handler(), store, caller, streams,
                assembly.agentId(), dependencies.dispatcher(), continuation);
        assembly.onClose(orchestrator::stopDispatching);
        var listener = assembly.component(TaskAdmissionListener.class).orElse(null);
        var executor = new A2AAgentExecutor(orchestrator, dependencies.protocol(),
                dependencies.admissionGate(), listener);
        localExecutor.set(executor);
        var processor = A2AAutoConfiguration.createEventProcessor(bus, store, sender, queues);
        var sdkHandler = new DefaultRequestHandler(executor, store, queues, pushConfigs, processor,
                dependencies.resources().agentExecutor(), dependencies.resources().eventConsumerExecutor());
        // Only the new SDK object needs @Inject/@PostConstruct. User handlers and
        // explicitly decorated stores never re-enter Spring bean post-processing.
        dependencies.beanFactory().autowireBean(sdkHandler);
        Object initialized = dependencies.beanFactory()
                .initializeBean(sdkHandler, "hostedSdkRequestHandler:" + assembly.agentId());
        if (!(initialized instanceof RequestHandler requestHandler)) {
            throw new IllegalStateException("Initialized SDK handler must implement RequestHandler");
        }
        assembly.execution = new HostedAgentRuntime.Execution(orchestrator, requestHandler, store, streams,
                executor, continuation, bus, processor, queues, pushConfigs, sender,
                new InMemoryA2aPushNotificationCallbackStore());
    }

    private RemoteAgentCaller boundCaller(String agentId) {
        return (call, observer) -> {
            Object selectedPush = call.metadata().get("runtime.a2a.callbackUrl");
            if (!(selectedPush instanceof String url) || url.isBlank()) {
                return dependencies.remoteCaller().callOutcome(call, observer);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(call.metadata());
            metadata.put("runtime.a2a.callbackUrl", dependencies.cards().callbackUrl(agentId));
            var bound = new RemoteCall(call.agentName(), call.message(), call.contextId(), call.taskId(), metadata,
                    call.messageMetadata(), call.isCallerStreaming(), call.parts());
            return dependencies.remoteCaller().callOutcome(bound, observer);
        };
    }

    static void cleanup(List<Runnable> actions) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            try {
                actions.get(i).run();
            } catch (RuntimeException failure) {
                log.error("Hosted resource cleanup failed type={}", failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Shared inputs resolved once by framework auto-configuration.
     */
    public record Dependencies(A2AProperties properties, MiddlewareProperties middleware,
            RuntimeRedisClient redisClient, RemoteAgentCaller remoteCaller, A2AProtocolAdapter protocol,
            TaskAdmissionGate admissionGate, HostedResources resources, RemoteInvocationDispatcher dispatcher,
            HostedAgentCardFactory cards, AutowireCapableBeanFactory beanFactory, HttpClient httpClient) {
    }

    /**
     * Framework module hook; not an application registration API.
     */
    public interface Extension {
        default TaskStore decorateTaskStore(Assembly assembly, TaskStore store) {
            return store;
        }

        default RemoteAgentCaller decorateRemoteCaller(Assembly assembly, RemoteAgentCaller caller) {
            return caller;
        }

        /**
         * Configures the original handler after its execution graph is assembled.
         *
         * @param assembly target-local assembly view
         */
        default void configureHandler(Assembly assembly) {
        }
    }

    /**
     * Controlled startup view; components cannot be rebound after publication.
     */
    public static final class Assembly {
        private final String applicationName;

        private final HostedAgentDefinitions.Entry entry;

        private final RuntimeRedisClient redisClient;

        private final Map<Class<?>, Object> components = new LinkedHashMap<>();

        private final List<Runnable> cleanup = new ArrayList<>();

        private TaskStore taskStore;

        private TaskStateProvider taskStateProvider;

        private HostedAgentRuntime.Execution execution;

        private Assembly(String applicationName, HostedAgentDefinitions.Entry entry, RuntimeRedisClient redisClient) {
            this.applicationName = applicationName;
            this.entry = entry;
            this.redisClient = redisClient;
        }

        public String applicationName() {
            return applicationName;
        }

        /**
         * Returns the registration ID used for routing and Runtime storage isolation.
         *
         * @return registration ID
         */
        public String agentId() {
            return entry.agentId();
        }

        /**
         * Returns the original registered handler instance.
         *
         * @return user handler
         */
        public AgentHandler handler() {
            return entry.handler();
        }

        /**
         * Returns the non-owning Redis view scoped to this registration.
         *
         * @return scoped client, or empty when Redis is disabled
         */
        public Optional<RuntimeRedisClient> redisClient() {
            return Optional.ofNullable(redisClient);
        }

        public TaskStore taskStore() {
            return taskStore;
        }

        /**
         * Returns the assembled execution graph for handler configuration.
         *
         * @return target-local execution graph
         * @throws IllegalStateException if execution is not assembled
         */
        public HostedAgentRuntime.Execution execution() {
            return Objects.requireNonNull(execution, "Execution graph is not assembled yet");
        }

        /**
         * Binds one framework dependency before runtime publication.
         *
         * @param <T> dependency type
         * @param type lookup type
         * @param component target-local dependency
         * @throws IllegalStateException if this type is already bound
         */
        public <T> void bind(Class<T> type, T component) {
            if (components.putIfAbsent(type, Objects.requireNonNull(component)) != null) {
                throw new IllegalStateException("Hosted component already bound: " + type.getName());
            }
        }

        /**
         * Looks up a framework dependency bound during assembly.
         *
         * @param <T> dependency type
         * @param type lookup type
         * @return bound dependency, or empty if not configured
         */
        public <T> Optional<T> component(Class<T> type) {
            return Optional.ofNullable(type.cast(components.get(type)));
        }

        /**
         * Registers cleanup, run in reverse order on rollback or shutdown.
         *
         * @param action cleanup action
         */
        public void onClose(Runnable action) {
            cleanup.add(Objects.requireNonNull(action));
        }
    }
}
