package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.lifecycle.AgentHandlerLoader;
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
import com.openjiuwen.service.app.orchestrator.DefaultServeOrchestrator;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties({ServiceProperties.class, QueryProperties.class, LifecycleProperties.class})
@ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
public class AgentServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandlerHolder agentHandlerHolder() {
        return new AgentHandlerHolder();
    }

    @Bean
    @ConditionalOnMissingBean(AgentHandlerLoader.class)
    public AgentHandlerLoader agentHandlerLoader(ServiceProperties serviceProperties) {
        return new AgentHandlerLoader(serviceProperties);
    }

    @Bean
    @ConditionalOnMissingBean(AgentServiceIdentity.class)
    public AgentServiceIdentity agentServiceIdentity(Environment environment) {
        return new DefaultAgentServiceIdentity(environment);
    }

    @Bean
    @ConditionalOnMissingBean(ActiveStreamRegistry.class)
    public ActiveStreamRegistry activeStreamRegistry() {
        return new ActiveStreamRegistry();
    }

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

    @Bean
    @ConditionalOnMissingBean(AgentLifecycleHooks.class)
    public AgentLifecycleHooks agentLifecycleHooks(
            List<AgentInitHook> initHooks,
            List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers) {
        return new AgentLifecycleHooks(initHooks, shutdownHooks, interruptHandlers);
    }

    @Bean
    @ConditionalOnMissingBean(InitPhaseExecutor.class)
    public InitPhaseExecutor initPhaseExecutor(
            AgentServiceIdentity identity,
            AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness,
            ObjectProvider<AgentHandler> agentHandlerProvider,
            AgentHandlerLoader agentHandlerLoader,
            LifecycleProperties lifecycleProperties) {
        return new InitPhaseExecutor(
                identity, hooks, readiness, agentHandlerProvider, agentHandlerLoader,
                lifecycleProperties);
    }

    @Bean
    @ConditionalOnMissingBean(ShutdownPhaseExecutor.class)
    public ShutdownPhaseExecutor shutdownPhaseExecutor(
            AgentServiceIdentity identity,
            AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness,
            ActiveStreamRegistry streamRegistry,
            ObjectProvider<AgentHandler> agentHandlerProvider,
            LifecycleProperties lifecycleProperties) {
        return new ShutdownPhaseExecutor(
                identity, hooks, readiness, streamRegistry, agentHandlerProvider, lifecycleProperties);
    }

    @Bean
    @ConditionalOnMissingBean(ActiveStreamInterruptor.class)
    public ActiveStreamInterruptor activeStreamInterruptor(
            ObjectProvider<ServeOrchestrator> orchestratorProvider,
            AgentLifecycleHooks hooks) {
        return new ActiveStreamInterruptor(orchestratorProvider, hooks.interruptHandlers());
    }

    @Bean
    @ConditionalOnMissingBean(AgentLifecycleManager.class)
    public DefaultAgentLifecycleManager agentLifecycleManager(
            InitPhaseExecutor initPhaseExecutor,
            ShutdownPhaseExecutor shutdownPhaseExecutor,
            ActiveStreamInterruptor streamInterruptor) {
        return new DefaultAgentLifecycleManager(initPhaseExecutor, shutdownPhaseExecutor, streamInterruptor);
    }

    @Bean
    @ConditionalOnMissingBean(AgentLifecycleBootstrap.class)
    public AgentLifecycleBootstrap agentLifecycleBootstrap(AgentLifecycleManager lifecycleManager) {
        return new AgentLifecycleBootstrap(lifecycleManager);
    }

    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    @ConditionalOnBean(AgentHandler.class)
    public ServeOrchestrator serveOrchestrator(AgentHandler agentHandler, ActiveStreamRegistry streamRegistry) {
        return new DefaultServeOrchestrator(agentHandler, streamRegistry);
    }
}
