/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.app.config.QueryProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.AgentHandlerHolder;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleBootstrap;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.app.lifecycle.InitPhaseExecutor;
import com.openjiuwen.service.app.lifecycle.ShutdownPhaseExecutor;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Auto-configuration for the agent service lifecycle, identity, and stream
 * management beans.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties({ServiceProperties.class, QueryProperties.class, LifecycleProperties.class})
@ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
public class AgentServiceAutoConfiguration {
    /**
     * Creates the placeholder agent handler holder when no custom handler is configured.
     *
     * @return the agent handler holder
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service", name = "agent-id", havingValue = "", matchIfMissing = true)
    public AgentHandlerHolder agentHandlerHolder() {
        return new AgentHandlerHolder();
    }

    /**
     * Creates the default agent service identity bean.
     *
     * @param environment the Spring environment
     * @return the agent service identity
     */
    @Bean
    @ConditionalOnMissingBean(AgentServiceIdentity.class)
    public AgentServiceIdentity agentServiceIdentity(Environment environment) {
        return new DefaultAgentServiceIdentity(environment);
    }

    /**
     * Creates the active stream registry bean.
     *
     * @return the active stream registry
     */
    @Bean
    @ConditionalOnMissingBean(ActiveStreamRegistry.class)
    public ActiveStreamRegistry activeStreamRegistry() {
        return new ActiveStreamRegistry();
    }

    /**
     * Creates the default agent readiness tracker bean.
     *
     * @return the default agent readiness tracker
     */
    @Bean
    @ConditionalOnMissingBean(DefaultAgentReadiness.class)
    public DefaultAgentReadiness defaultAgentReadiness() {
        return new DefaultAgentReadiness();
    }

    @Bean
    @ConditionalOnMissingBean(AgentReadiness.class)
    public AgentReadiness agentReadiness(DefaultAgentReadiness readiness) {
        return readiness;
    }

    /**
     * Creates the agent lifecycle hooks bean from registered init, shutdown, and
     * interrupt handlers.
     *
     * @param initHooks the init hook implementations
     * @param shutdownHooks the shutdown hook implementations
     * @param interruptHandlers the interrupt handler implementations
     * @return the lifecycle hooks aggregate
     */
    @Bean
    @ConditionalOnMissingBean(AgentLifecycleHooks.class)
    public AgentLifecycleHooks agentLifecycleHooks(List<AgentInitHook> initHooks, List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers) {
        return new AgentLifecycleHooks(initHooks, shutdownHooks, interruptHandlers);
    }

    /**
     * Creates the init-phase executor bean.
     *
     * @param identity the agent service identity
     * @param hooks the lifecycle hooks
     * @param readiness the agent readiness tracker
     * @param agentHandlerProvider the agent handler provider
     * @param lifecycleProperties the lifecycle configuration
     * @return the init phase executor
     */
    @Bean
    @ConditionalOnMissingBean(InitPhaseExecutor.class)
    public InitPhaseExecutor initPhaseExecutor(AgentServiceIdentity identity, AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness, ObjectProvider<AgentHandler> agentHandlerProvider,
            LifecycleProperties lifecycleProperties) {
        return new InitPhaseExecutor(identity, hooks, readiness, agentHandlerProvider, lifecycleProperties);
    }

    /**
     * Creates the shutdown-phase executor bean.
     *
     * @param identity the agent service identity
     * @param hooks the lifecycle hooks
     * @param readiness the agent readiness tracker
     * @param streamRegistry the active stream registry
     * @param agentHandlerProvider the agent handler provider
     * @param lifecycleProperties the lifecycle configuration
     * @return the shutdown phase executor
     */
    @Bean
    @ConditionalOnMissingBean(ShutdownPhaseExecutor.class)
    public ShutdownPhaseExecutor shutdownPhaseExecutor(AgentServiceIdentity identity, AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness, ActiveStreamRegistry streamRegistry,
            ObjectProvider<AgentHandler> agentHandlerProvider, LifecycleProperties lifecycleProperties) {
        return new ShutdownPhaseExecutor(identity, hooks, readiness, streamRegistry, agentHandlerProvider,
                lifecycleProperties);
    }

    /**
     * Creates the active stream interruptor bean.
     *
     * @param orchestratorProvider the orchestrator provider
     * @param hooks the lifecycle hooks
     * @return the active stream interruptor
     */
    @Bean
    @ConditionalOnMissingBean(ActiveStreamInterruptor.class)
    public ActiveStreamInterruptor activeStreamInterruptor(ObjectProvider<ServeOrchestrator> orchestratorProvider,
            AgentLifecycleHooks hooks) {
        return new ActiveStreamInterruptor(orchestratorProvider, hooks.interruptHandlers());
    }

    /**
     * Creates the agent lifecycle manager bean.
     *
     * @param initPhaseExecutor the init phase executor
     * @param shutdownPhaseExecutor the shutdown phase executor
     * @param streamInterruptor the stream interruptor
     * @return the default agent lifecycle manager
     */
    @Bean
    @ConditionalOnMissingBean(AgentLifecycleManager.class)
    public DefaultAgentLifecycleManager agentLifecycleManager(InitPhaseExecutor initPhaseExecutor,
            ShutdownPhaseExecutor shutdownPhaseExecutor, ActiveStreamInterruptor streamInterruptor) {
        return new DefaultAgentLifecycleManager(initPhaseExecutor, shutdownPhaseExecutor, streamInterruptor);
    }

    /**
     * Creates the agent lifecycle bootstrap bean.
     *
     * @param lifecycleManager the lifecycle manager
     * @return the lifecycle bootstrap
     */
    @Bean
    @ConditionalOnMissingBean(AgentLifecycleBootstrap.class)
    public AgentLifecycleBootstrap agentLifecycleBootstrap(AgentLifecycleManager lifecycleManager) {
        return new AgentLifecycleBootstrap(lifecycleManager);
    }
}
