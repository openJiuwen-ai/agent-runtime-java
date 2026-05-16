package com.openjiuwen.versatile_adapter;

import com.openjiuwen.versatile_adapter.adapter.AgentCardConfig;
import com.openjiuwen.versatile_adapter.adapter.Executor;
import com.openjiuwen.versatile_adapter.adapter.VersatileProxy;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.events.MainEventBus;
import io.a2a.server.events.MainEventBusProcessor;
import io.a2a.server.events.QueueManager;
import io.a2a.server.config.A2AConfigProvider;
import io.a2a.server.config.DefaultValuesConfigProvider;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.PushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.AgentCard;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * VersatileAdapter Spring Boot Application。
 *
 * 对应 Python: app.py
 *
 * 启动流程（与 Python lifespan 对齐）：
 *   1. 创建 VersatileProxy
 *   2. 创建 Executor（AgentExecutor 实现）
 *   3. 创建 InMemoryTaskStore
 *   4. 创建 DefaultRequestHandler（绑定 executor + taskStore）
 *   5. 挂载 A2A 路由（A2ARouter 处理 agent-card + JSON-RPC）
 *   6. 打印 "[VersatileAdapter] 启动完成"
 *
 * 暴露端点（A2A SDK 标准）：
 *   GET  /.well-known/agent-card.json  — AgentCard
 *   POST /                             — A2A JSON-RPC（message/send、message/stream）
 */
@SpringBootApplication
@EnableConfigurationProperties(Config.class)
public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    private MainEventBusProcessor eventBusProcessor;
    private ExecutorService agentExecutorSvc;
    private ExecutorService eventConsumerSvc;

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    // ── Bean 定义（与 Python lifespan 中的初始化顺序对齐）──────────────────

    @Bean
    public A2AConfigProvider a2aConfigProvider() {
        return new DefaultValuesConfigProvider();
    }

    @Bean
    public VersatileProxy versatileProxy(Config config) {
        return new VersatileProxy(config);
    }

    @Bean
    public Executor executor(VersatileProxy versatileProxy) {
        return new Executor(versatileProxy);
    }

    @Bean
    public AgentCard versatileAdapterCard() {
        return AgentCardConfig.VERSATILE_ADAPTER_CARD;
    }

    /**
     * InMemoryTaskStore — 对应 Python 的 InMemoryTaskStore()。
     */
    @Bean
    public TaskStore taskStore() {
        return new InMemoryTaskStore();
    }

    /**
     * PushNotificationConfigStore — 内存实现。
     */
    @Bean
    public PushNotificationConfigStore pushNotificationConfigStore() {
        return new InMemoryPushNotificationConfigStore();
    }

    /**
     * MainEventBus — 事件总线，用于在 AgentExecutor 和事件消费者之间传递事件。
     */
    @Bean
    public MainEventBus mainEventBus() {
        return new MainEventBus();
    }

    /**
     * QueueManager — 对应 Python SDK 中 TaskUpdater 使用的事件队列。
     */
    @Bean
    public QueueManager queueManager(TaskStore taskStore, MainEventBus mainEventBus) {
        InMemoryTaskStore memoryStore = (InMemoryTaskStore) taskStore;
        return new InMemoryQueueManager(memoryStore, mainEventBus);
    }

    /**
     * PushNotificationSender — 空实现，避免 MainEventBusProcessor 中 NPE。
     * 当前场景不需要 push notification，提供 no-op 即可。
     */
    @Bean
    public PushNotificationSender pushNotificationSender() {
        return event -> { /* no-op */ };
    }

    /**
     * MainEventBusProcessor — 消费事件总线上的事件，更新 TaskStore。
     */
    @Bean
    public MainEventBusProcessor mainEventBusProcessor(
            MainEventBus mainEventBus,
            TaskStore taskStore,
            QueueManager queueManager,
            PushNotificationSender pushNotificationSender) {
        this.eventBusProcessor = new MainEventBusProcessor(mainEventBus, taskStore, pushNotificationSender, queueManager);
        this.eventBusProcessor.ensureStarted();
        return this.eventBusProcessor;
    }

    /**
     * Agent 执行线程池 — 运行 AgentExecutor.execute()，必须在独立线程，
     * 否则会阻塞 EventConsumer 的消费循环，导致事件积压到 execute() 结束才一次性推送。
     */
    @Bean
    public ExecutorService agentExecutorService() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Event 消费线程池 — 运行 EventConsumer 的轮询循环，逐帧从 EventQueue 取事件推给 SSE。
     */
    @Bean
    public ExecutorService eventConsumerExecutorService() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "event-consumer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * DefaultRequestHandler — 对应 Python 的 DefaultRequestHandler(agent_executor, task_store, agent_card)。
     *
     * 这是 A2A 请求处理的核心：接收 JSON-RPC 请求 → 调度到 AgentExecutor → 管理任务状态。
     */
    @Bean
    public DefaultRequestHandler requestHandler(
            Executor executor,
            TaskStore taskStore,
            QueueManager queueManager,
            PushNotificationConfigStore pushConfigStore,
            MainEventBusProcessor eventBusProcessor,
            ExecutorService agentExecutorService,
            ExecutorService eventConsumerExecutorService,
            Config config) {
        this.agentExecutorSvc = agentExecutorService;
        this.eventConsumerSvc = eventConsumerExecutorService;

        logger.info("[VersatileAdapter] 开始初始化 DefaultRequestHandler...");

        DefaultRequestHandler handler = DefaultRequestHandler.create(
                executor,
                taskStore,
                queueManager,
                pushConfigStore,
                eventBusProcessor,
                agentExecutorService,            // agent executor（独立线程池）
                eventConsumerExecutorService     // event consumer executor（独立线程池）
        );

        logger.info("[VersatileAdapter] 启动完成，Versatile URL template: {}",
                config.getVersatileUrlTemplate());

        return handler;
    }

    @PreDestroy
    public void onShutdown() {
        if (agentExecutorSvc != null) {
            agentExecutorSvc.shutdownNow();
        }
        if (eventConsumerSvc != null) {
            eventConsumerSvc.shutdownNow();
        }
        logger.info("[VersatileAdapter] 关闭完成");
    }
}
