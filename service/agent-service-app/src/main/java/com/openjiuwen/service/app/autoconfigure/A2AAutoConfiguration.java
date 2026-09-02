/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.SpringEnvironmentConfigProvider;
import com.openjiuwen.service.app.controller.a2a.A2AAgentExecutor;
import com.openjiuwen.service.app.controller.a2a.A2AProtocolAdapter;
import com.openjiuwen.service.app.controller.a2a.A2ATaskContinuation;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackHandler;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackStore;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCapabilityGate;
import com.openjiuwen.service.app.controller.a2a.HttpPushNotificationSender;
import com.openjiuwen.service.app.controller.a2a.InMemoryA2aPushNotificationCallbackStore;
import com.openjiuwen.service.app.controller.a2a.NoOpA2aPushNotificationCallbackHandler;
import com.openjiuwen.service.app.controller.a2a.RedisTaskStore;
import com.openjiuwen.service.app.controller.a2a.ResilientMainEventBusProcessor;
import com.openjiuwen.service.app.controller.a2a.WriteThrottlingTaskStore;
import com.openjiuwen.service.app.controller.a2a.client.A2AAgentCardDiscovery;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.config.DefaultValuesConfigProvider;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.MainEventBusProcessorCallback;
import org.a2aproject.sdk.server.events.NoTaskQueueException;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-configuration for A2A Server + Client beans. Activated only when {@code
 * a2a-java-sdk-server-common} is on the classpath.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = {AgentServiceAutoConfiguration.class, RedisMiddlewareAutoConfiguration.class})
@ConditionalOnClass(AgentExecutor.class)
@EnableConfigurationProperties(A2AProperties.class)
public class A2AAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(A2AAutoConfiguration.class);

    private static final Map<String, String> A2A_RUNTIME_DEFAULTS = Map.of("a2a.blocking.agent.timeout.seconds", "300");

    /**
     * Admission gate provider (optional; the gate bean typically comes from the
     * ext module's concurrency auto-configuration). Field-injected so the
     * {@code a2aRequestHandler} bean method stays within five parameters.
     */
    @Autowired
    private ObjectProvider<TaskAdmissionGate> admissionGateProvider;

    /**
     * Creates the SDK main event bus bean.
     *
     * @return the main event bus
     */
    @Bean
    @ConditionalOnMissingBean
    public MainEventBus a2aMainEventBus() {
        return new MainEventBus();
    }

    /**
     * Creates the task store bean, using Redis if configured or in-memory as default.
     *
     * @param middlewareProvider the middleware properties provider
     * @param redisClientProvider the runtime Redis client provider
     * @param a2aProperties the A2A configuration properties
     * @return the task store
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskStore a2aTaskStore(ObjectProvider<MiddlewareProperties> middlewareProvider,
            ObjectProvider<RuntimeRedisClient> redisClientProvider, A2AProperties a2aProperties) {
        MiddlewareProperties middlewareProperties = middlewareProvider.getIfAvailable();
        if (middlewareProperties != null && "redis".equals(middlewareProperties.getCheckpointer().getType())) {
            RuntimeRedisClient redisClient = redisClientProvider.getIfAvailable();
            if (redisClient == null) {
                throw new IllegalStateException("RuntimeRedisClient is required for redis A2A task store");
            }
            // Wrap in a read-through/write-behind cache: the SDK persists the task on every streaming event, so a
            // raw Redis round-trip per LLM chunk would throttle the SSE stream to network speed. See
            // WriteThrottlingTaskStore for the full rationale.
            return new WriteThrottlingTaskStore(
                    new RedisTaskStore(redisClient, middlewareProperties.getCheckpointer().getTtlSeconds()),
                    a2aProperties.getTaskStoreWriteThrottleMs());
        }
        return new InMemoryTaskStore();
    }

    /**
     * Creates the push notification config store bean.
     *
     * @return the push notification config store
     */
    @Bean
    @ConditionalOnMissingBean
    public PushNotificationConfigStore a2aPushNotificationConfigStore() {
        return new InMemoryPushNotificationConfigStore();
    }

    /**
     * Creates the HTTP push notification sender bean.
     *
     * @param pushConfigStore the push notification config store
     * @return the no-op push notification sender
     */
    @Bean
    @ConditionalOnMissingBean
    public PushNotificationSender a2aPushNotificationSender(PushNotificationConfigStore pushConfigStore) {
        return new HttpPushNotificationSender(pushConfigStore);
    }

    /**
     * Creates the push notification callback idempotency store bean.
     *
     * @return the callback idempotency store
     */
    @Bean
    @ConditionalOnMissingBean
    public A2aPushNotificationCallbackStore a2aPushNotificationCallbackStore() {
        return new InMemoryA2aPushNotificationCallbackStore();
    }

    /**
     * Creates the push notification capability gate bean.
     *
     * @param properties the A2A configuration properties
     * @param pushNotificationSender the push notification sender
     * @param callbackStore the callback idempotency store
     * @param callbackHandler the callback handler
     * @return the push notification capability gate
     */
    @Bean
    @ConditionalOnMissingBean
    public A2aPushNotificationCapabilityGate a2aPushNotificationCapabilityGate(A2AProperties properties,
            PushNotificationSender pushNotificationSender, A2aPushNotificationCallbackStore callbackStore,
            A2aPushNotificationCallbackHandler callbackHandler) {
        return new A2aPushNotificationCapabilityGate(properties, pushNotificationSender, callbackStore,
                callbackHandler);
    }

    /**
     * Creates the queue manager bean backed by the task store. Stores that
     * implement {@link TaskStateProvider} also drive queue lifecycle: finalized
     * tasks get their queue removed from the manager's map. Stores without that
     * capability keep the legacy behavior (queues are never auto-removed).
     *
     * @param taskStore the task store
     * @param mainEventBus the main event bus
     * @return the queue manager
     */
    @Bean
    @ConditionalOnMissingBean
    public QueueManager a2aQueueManager(TaskStore taskStore, MainEventBus mainEventBus) {
        if (taskStore instanceof TaskStateProvider provider) {
            return new InMemoryQueueManager(provider, mainEventBus);
        }
        return new InMemoryQueueManager(null, mainEventBus);
    }

    /**
     * Creates the main event bus processor bean. The resilient variant restarts
     * the processing loop after an unexpected death, and the finalize callback
     * closes the task's queue so abandoned queues do not accumulate.
     *
     * @param mainEventBus the main event bus
     * @param taskStore the task store
     * @param pushSender the push notification sender
     * @param queueManager the queue manager
     * @return the main event bus processor
     */
    @Bean
    @ConditionalOnMissingBean
    public MainEventBusProcessor a2aMainEventBusProcessor(MainEventBus mainEventBus, TaskStore taskStore,
            PushNotificationSender pushSender, QueueManager queueManager) {
        ResilientMainEventBusProcessor processor = new ResilientMainEventBusProcessor(mainEventBus, taskStore,
                pushSender, queueManager);
        processor.setCallback(new MainEventBusProcessorCallback() {
            @Override
            public void onEventProcessed(String taskId, Event event) {
                // Intentionally empty: production keeps the SDK's default no-op behavior.
            }

            @Override
            public void onTaskFinalized(String taskId) {
                try {
                    queueManager.close(taskId);
                } catch (NoTaskQueueException e) {
                    // Already closed or absent -- nothing to clean up for this task.
                    log.debug("A2A task {} queue already closed or absent", taskId);
                }
            }
        });
        return processor;
    }

    /**
     * Creates the A2A config provider bean backed by the Spring environment and SDK defaults.
     *
     * @param environment the Spring environment
     * @param beanFactory the bean factory used to initialize the SDK defaults provider
     * @return the A2A config provider
     */
    @Bean
    @ConditionalOnMissingBean
    public A2AConfigProvider a2aConfigProvider(Environment environment, AutowireCapableBeanFactory beanFactory) {
        DefaultValuesConfigProvider defaults = beanFactory.createBean(DefaultValuesConfigProvider.class);
        return new SpringEnvironmentConfigProvider(environment, defaults, A2A_RUNTIME_DEFAULTS);
    }

    /**
     * Creates the A2A protocol adapter bean.
     *
     * @return the A2A protocol adapter
     */
    @Bean
    @ConditionalOnMissingBean
    public A2AProtocolAdapter a2aProtocolAdapter() {
        return new A2AProtocolAdapter();
    }

    /**
     * Creates the A2A agent executor bean.
     *
     * @param orchestrator the serve orchestrator
     * @param adapter the A2A protocol adapter
     * @param admissionGateProvider the admission gate provider (optional; the
     *        gate bean typically comes from the ext module's concurrency
     *        auto-configuration)
     * @param admissionListenerProvider the admission lifecycle listener
     *        provider (optional; the listener bean typically comes from the
     *        ext module's concurrency auto-configuration)
     * @return the A2A agent executor
     */
    @Bean
    @ConditionalOnMissingBean
    public A2AAgentExecutor a2aAgentExecutor(ServeOrchestrator orchestrator, A2AProtocolAdapter adapter,
            ObjectProvider<TaskAdmissionGate> admissionGateProvider,
            ObjectProvider<TaskAdmissionListener> admissionListenerProvider) {
        return new A2AAgentExecutor(orchestrator, adapter, admissionGateProvider.getIfAvailable(),
                admissionListenerProvider.getIfAvailable());
    }

    /**
     * Creates the internal execution resources shared by the SDK request handler and callback continuations.
     *
     * @param eventBusProcessor the SDK event bus processor
     * @param properties the A2A runtime properties carrying the optional
     *        agent execution pool size
     * @return the A2A execution resources
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    A2AExecutionResources a2aExecutionResources(MainEventBusProcessor eventBusProcessor, A2AProperties properties) {
        return new A2AExecutionResources(eventBusProcessor, properties.getAgentThreads());
    }

    /**
     * Creates the callback task continuation adapter.
     *
     * @param taskStore the A2A task store
     * @param queueManager the SDK queue manager
     * @param agentExecutorProvider lazy agent executor provider to avoid a bean cycle
     * @param executionResources the internal A2A execution resources
     * @return the task continuation adapter
     */
    @Bean
    @ConditionalOnMissingBean
    public A2ATaskContinuation a2aTaskContinuation(TaskStore taskStore, QueueManager queueManager,
            ObjectProvider<A2AAgentExecutor> agentExecutorProvider,
            A2AExecutionResources executionResources) {
        return new A2ATaskContinuation(taskStore, queueManager, agentExecutorProvider,
                executionResources.agentExecutor());
    }

    /**
     * Creates the remote agent card registry bean.
     *
     * @param eventPublisher the Spring application event publisher
     * @return the remote agent card registry
     */
    @Bean
    @ConditionalOnMissingBean
    public A2ARemoteAgentCardRegistry a2aRemoteAgentCardRegistry(ApplicationEventPublisher eventPublisher) {
        return new A2ARemoteAgentCardRegistry(eventPublisher);
    }

    /**
     * Creates the default {@link RemoteAgentCaller} bean. Deployments may
     * override with an {@code A2AGatewayRemoteAgentCaller}.
     *
     * @param registry the remote agent card registry
     * @param props A2A runtime properties
     * @return the default remote agent caller
     */
    @Bean
    @ConditionalOnMissingBean(RemoteAgentCaller.class)
    public A2ARemoteAgentClient defaultRemoteAgentCaller(A2ARemoteAgentCardRegistry registry, A2AProperties props) {
        return new A2ARemoteAgentClient(registry, props.getRemoteInvocation().getMaxConcurrency());
    }

    /**
     * Creates the agent card discovery bean for fetching remote agent cards at
     * startup. Also serves as the baseline {@link RemoteAgentCardResolver};
     * deployments may override with an {@code A2AGatewayCardResolver} for
     * cross-origin cards.
     *
     * @param props the A2A properties
     * @param registry the remote agent card registry
     * @return the agent card discovery
     */
    @Bean
    @ConditionalOnMissingBean(RemoteAgentCardResolver.class)
    public A2AAgentCardDiscovery a2aAgentCardDiscovery(A2AProperties props, A2ARemoteAgentCardRegistry registry) {
        return new A2AAgentCardDiscovery(props, registry);
    }

    /**
     * Creates the A2A-enabled serve orchestrator bean as the default orchestrator.
     *
     * @param agentHandler the agent handler
     * @param taskStore the task store
     * @param remoteAgentCaller the remote agent caller SPI
     * @param streamRegistry the active stream registry
     * @param agentId the application name used as the agent identifier for shadow task namespacing
     * @param props A2A runtime properties
     * @param continuation the callback task continuation adapter
     * @return the A2A-enabled serve orchestrator
     */
    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    public A2AEnabledServeOrchestrator a2aEnabledServeOrchestrator(AgentHandler agentHandler, TaskStore taskStore,
            RemoteAgentCaller remoteAgentCaller, ActiveStreamRegistry streamRegistry,
            @Value("${spring.application.name:agent}") String agentId, A2AProperties props,
            A2ATaskContinuation continuation) {
        A2AProperties.RemoteInvocationProperties limits = props.getRemoteInvocation();
        return new A2AEnabledServeOrchestrator(agentHandler, taskStore, remoteAgentCaller, streamRegistry, agentId,
                limits.getMaxConcurrency(), limits.getMaxQueueSize(), limits.getQueueTimeoutSeconds(), continuation);
    }

    /**
     * Creates the default no-op push notification callback handler bean.
     *
     * @return the no-op callback handler
     */
    @Bean
    @ConditionalOnMissingBean
    public A2aPushNotificationCallbackHandler a2aPushNotificationCallbackHandler() {
        return new NoOpA2aPushNotificationCallbackHandler();
    }

    /**
     * Creates the A2A request handler bean with explicit thread pool executors.
     *
     * @param agentExecutor the agent executor
     * @param taskStore the task store
     * @param queueManager the queue manager
     * @param pushConfigStore the push notification config store
     * @param executionResources the internal A2A execution resources
     * @return the request handler
     */
    @Bean
    @ConditionalOnMissingBean
    public RequestHandler a2aRequestHandler(A2AAgentExecutor agentExecutor, TaskStore taskStore,
            QueueManager queueManager, PushNotificationConfigStore pushConfigStore,
            A2AExecutionResources executionResources) {
        validateAdmissionCapacity(admissionGateProvider.getIfAvailable(), executionResources);
        return DefaultRequestHandler.create(agentExecutor, taskStore, queueManager, pushConfigStore,
                executionResources.eventBusProcessor(), executionResources.agentExecutor(),
                executionResources.eventConsumerExecutor());
    }

    private static void validateAdmissionCapacity(TaskAdmissionGate admissionGate, A2AExecutionResources resources) {
        if (admissionGate == null) {
            return;
        }
        int limit = admissionGate.limit();
        int capacity = resources.agentConcurrencyCapacity();
        if (limit > capacity) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "Task admission limit (%d) exceeds the A2A agent execution capacity (%d threads). "
                            + "Lower the concurrency limit, or set openjiuwen.service.a2a.agent-threads "
                            + "to raise the agent executor capacity, otherwise admission permits would be "
                            + "held by queued (not running) tasks.",
                    limit, capacity));
        }
    }
}

final class A2AExecutionResources {
    /**
     * Auto-sized agent pool floor — the lower bound applied when
     * {@code availableProcessors * AUTO_POOL_MULTIPLIER} falls below this value.
     * I/O-bound agent tasks park on remote LLM or backend calls, so the baseline
     * mirrors {@code QuerySsePumpExecutor}. On machines with 8 or fewer cores this
     * floor dominates, yielding a fixed 64-slot pool.
     */
    static final int AUTO_POOL_FLOOR = 64;

    /**
     * Auto-sized agent pool multiplier per CPU core. Combined with
     * {@link #AUTO_POOL_FLOOR} via {@code max(floor, cores * multiplier)}.
     */
    static final int AUTO_POOL_MULTIPLIER = 8;

    private static final Logger log = LoggerFactory.getLogger(A2AExecutionResources.class);

    /** Capacity of the agent task submission queue. */
    private static final int AGENT_QUEUE_CAPACITY = 256;

    /** Idle timeout of event consumer threads before reclamation. */
    private static final long EVENT_CONSUMER_KEEP_ALIVE_SECONDS = 60L;

    /**
     * Floor for the event consumer hard cap. The effective cap is derived per
     * instance from the effective agent pool size, so every task the
     * admission gate may admit fits; the cap only trips on consumer-thread
     * leaks, where failing the stream loudly beats growing toward OOM.
     */
    private static final int EVENT_CONSUMER_CAP_FLOOR = 256;

    private static final AtomicInteger EVENT_CONSUMER_THREAD_SEQ = new AtomicInteger(1);

    private final MainEventBusProcessor eventBusProcessor;

    private final ExecutorService agentExecutor;

    /**
     * Event consumer executor delivering streamed events to SSE subscribers.
     *
     * <p>On runtimes without virtual-thread support, this mirrors the SDK's
     * default {@code EventConsumerExecutorProducer}: each active event queue
     * occupies one consumer thread for the whole stream lifetime. Threads are
     * created on demand and reclaimed after the idle timeout, with an explicit
     * hard cap acting as a leak guard. On runtimes with virtual-thread support,
     * each consumer task runs in its own virtual thread without that executor
     * cap.</p>
     */
    private final ExecutorService eventConsumerExecutor;

    private final int eventConsumerMaxThreads;

    A2AExecutionResources(MainEventBusProcessor eventBusProcessor, int configuredAgentThreads) {
        this.eventBusProcessor = eventBusProcessor;
        int agentThreads = configuredAgentThreads > 0 ? configuredAgentThreads : autoAgentPoolSize();
        this.agentExecutor = newAgentExecutor(agentThreads);
        this.eventConsumerMaxThreads = eventConsumerMaxThreads(agentThreads);
        this.eventConsumerExecutor = newEventConsumerExecutor(eventConsumerMaxThreads);
    }

    private static ExecutorService newAgentExecutor(int agentThreads) {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("a2a-agent",
                    (thread, error) -> log.error("Uncaught A2A agent execution error thread={}",
                            thread.getName(), error));
        }
        return new ThreadPoolExecutor(agentThreads, agentThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(AGENT_QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static int eventConsumerMaxThreads(int agentThreads) {
        if (VirtualThreadSupport.isSupported()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(EVENT_CONSUMER_CAP_FLOOR, agentThreads);
    }

    private static ExecutorService newEventConsumerExecutor(int maximumPoolSize) {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("a2a-event-consumer",
                    A2AExecutionResources::logUncaught);
        }
        return new ThreadPoolExecutor(
                0, maximumPoolSize, EVENT_CONSUMER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "a2a-event-consumer-"
                            + EVENT_CONSUMER_THREAD_SEQ.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler(A2AExecutionResources::logUncaught);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    static int autoAgentPoolSize() {
        return Math.max(AUTO_POOL_FLOOR, Runtime.getRuntime().availableProcessors() * AUTO_POOL_MULTIPLIER);
    }

    /**
     * Uncaught-exception handler for event consumer threads. The polling loop in
     * the SDK's EventConsumer guards most failures internally and routes them to
     * the stream subscriber; anything escaping to the thread boundary means the
     * consumer died without completing its stream — log it loudly so the leak is
     * visible instead of silently losing the stream.
     *
     * @param thread the consumer thread that threw
     * @param throwable the uncaught throwable
     */
    private static void logUncaught(Thread thread, Throwable throwable) {
        log.error("A2A event consumer thread died with uncaught exception thread={}", thread.getName(),
                throwable);
    }

    int eventConsumerMaxThreads() {
        return eventConsumerMaxThreads;
    }

    Executor agentExecutor() {
        return agentExecutor;
    }

    int agentConcurrencyCapacity() {
        if (agentExecutor instanceof ThreadPoolExecutor platformExecutor) {
            return platformExecutor.getMaximumPoolSize();
        }
        // Per-task virtual threads have no executor-level concurrency limit;
        // the business admission gate still applies.
        return Integer.MAX_VALUE;
    }

    MainEventBusProcessor eventBusProcessor() {
        return eventBusProcessor;
    }

    Executor eventConsumerExecutor() {
        return eventConsumerExecutor;
    }

    void shutdown() {
        agentExecutor.shutdown();
        eventConsumerExecutor.shutdown();
    }
}
