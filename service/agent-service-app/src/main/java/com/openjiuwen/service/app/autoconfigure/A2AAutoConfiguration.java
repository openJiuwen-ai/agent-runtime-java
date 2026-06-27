/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisConnectionAssembler;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisJedisClientFactory;
import com.openjiuwen.service.app.controller.a2a.RedisTaskStore;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.A2AAgentExecutor;
import com.openjiuwen.service.app.controller.a2a.A2AProtocolAdapter;
import com.openjiuwen.service.app.controller.a2a.client.A2AAgentCardDiscovery;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.config.DefaultValuesConfigProvider;
import org.a2aproject.sdk.server.events.*;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import redis.clients.jedis.Jedis;

import java.util.concurrent.Executors;

/**
 * Auto-configuration for A2A Server + Client beans.
 * Activated only when {@code a2a-java-sdk-server-common} is on the classpath.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = AgentServiceAutoConfiguration.class)
@ConditionalOnClass(AgentExecutor.class)
@EnableConfigurationProperties(A2AProperties.class)
public class A2AAutoConfiguration {

    // ======================== SDK Infrastructure ========================

    @Bean
    @ConditionalOnMissingBean
    public MainEventBus a2aMainEventBus() {
        return new MainEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskStore a2aTaskStore(ObjectProvider<MiddlewareProperties> middlewareProvider,
                                   ObjectProvider<CredentialDecryptor> decryptorProvider) {
        MiddlewareProperties middlewareProperties = middlewareProvider.getIfAvailable();
        if (middlewareProperties != null
                && "redis".equals(middlewareProperties.getCheckpointer().getType())) {
            CredentialDecryptor decryptor = decryptorProvider.getIfAvailable();
            String ref = middlewareProperties.getCheckpointer().getRedisRef();
            var endpoint = RedisConnectionAssembler.resolveEndpoint(middlewareProperties, ref);
            String pwd = decryptor != null ? decryptor.decrypt(endpoint.getEncryptedPassword()) : "";
            Jedis jedis = RedisJedisClientFactory.createClient(endpoint, pwd);
            return new RedisTaskStore(jedis);
        }
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public PushNotificationConfigStore a2aPushNotificationConfigStore() {
        return new InMemoryPushNotificationConfigStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public PushNotificationSender a2aPushNotificationSender() {
        return (event, task) -> {};
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueManager a2aQueueManager(TaskStore taskStore, MainEventBus mainEventBus) {
        return new InMemoryQueueManager((TaskStateProvider) taskStore, mainEventBus);
    }

    @Bean
    @ConditionalOnMissingBean
    public MainEventBusProcessor a2aMainEventBusProcessor(
            MainEventBus mainEventBus, TaskStore taskStore,
            PushNotificationSender pushSender, QueueManager queueManager) {
        return new MainEventBusProcessor(mainEventBus, taskStore, pushSender, queueManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AConfigProvider a2aConfigProvider() {
        return new DefaultValuesConfigProvider();
    }

    // ======================== Business Beans ========================

    @Bean
    @ConditionalOnMissingBean
    public A2AProtocolAdapter a2aProtocolAdapter() {
        return new A2AProtocolAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AAgentExecutor a2aAgentExecutor(ServeOrchestrator orchestrator,
                                              A2AProtocolAdapter adapter) {
        return new A2AAgentExecutor(orchestrator, adapter);
    }

    @Bean
    @ConditionalOnMissingBean
    public A2ARemoteAgentCardRegistry a2aRemoteAgentCardRegistry() {
        return new A2ARemoteAgentCardRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public A2ARemoteAgentClient a2aRemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        return new A2ARemoteAgentClient(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AAgentCardDiscovery a2aAgentCardDiscovery(A2AProperties props,
            A2ARemoteAgentCardRegistry registry) {
        return new A2AAgentCardDiscovery(props, registry);
    }

    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    public A2AEnabledServeOrchestrator a2aEnabledServeOrchestrator(
            AgentHandler agentHandler, TaskStore taskStore,
            A2ARemoteAgentClient a2aClient, A2ARemoteAgentCardRegistry registry,
            ActiveStreamRegistry streamRegistry) {
        return new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, registry, streamRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestHandler a2aRequestHandler(A2AAgentExecutor agentExecutor, TaskStore taskStore,
            QueueManager queueManager, PushNotificationConfigStore pushConfigStore,
            MainEventBusProcessor eventBusProcessor) {
        return DefaultRequestHandler.create(
                agentExecutor, taskStore, queueManager,
                pushConfigStore, eventBusProcessor,
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()),
                Executors.newFixedThreadPool(2));
    }
}
