/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.foundation.tool.mcp.McpClientProvider;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreMcpClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreMcpClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for agent-core external service adapters.
 *
 * @since 2026-06-24
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentCoreExternalProperties.class)
public class AgentCoreAdaptersAutoConfiguration {
    /**
     * Creates the default MCP client decorator factory.
     *
     * @return default MCP client decorator factory bean
     */
    @Bean
    @ConditionalOnMissingBean(AgentCoreMcpClientDecoratorFactory.class)
    public AgentCoreMcpClientDecoratorFactory agentCoreMcpClientDecoratorFactory() {
        return new DefaultAgentCoreMcpClientDecoratorFactory();
    }

    /**
     * Creates the default remote client decorator factory.
     *
     * @return default remote client decorator factory bean
     */
    @Bean
    @ConditionalOnMissingBean(AgentCoreRemoteClientDecoratorFactory.class)
    public AgentCoreRemoteClientDecoratorFactory agentCoreRemoteClientDecoratorFactory() {
        return new DefaultAgentCoreRemoteClientDecoratorFactory();
    }

    /**
     * Creates the registrar that wires external service adapters into agent-core
     * providers.
     *
     * @param properties external adapter properties
     * @param decoratorFactory MCP client decorator factory
     * @param remoteDecoratorFactory remote client decorator factory
     * @param mcpClientProviders custom MCP client providers
     * @param remoteClientProviders custom remote client providers
     * @return external service adapter registrar bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalSvcAdapterRegistrar.class)
    public ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar(AgentCoreExternalProperties properties,
        AgentCoreMcpClientDecoratorFactory decoratorFactory,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        ObjectProvider<McpClientProvider> mcpClientProviders,
        ObjectProvider<RemoteClientProvider> remoteClientProviders) {
        return new DefaultExternalSvcAdapterRegistrar(properties, decoratorFactory, remoteDecoratorFactory,
            mcpClientProviders.orderedStream().toList(), remoteClientProviders.orderedStream().toList());
    }

    /**
     * Creates the default remote client factory when configured remote clients
     * exist.
     *
     * @param properties external adapter properties
     * @param remoteDecoratorFactory remote client decorator factory
     * @param remoteClientProviders custom remote client providers
     * @return default remote client factory bean
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.external.remote.clients[0]", name = "id")
    @ConditionalOnMissingBean(AgentCoreRemoteClientFactory.class)
    public AgentCoreRemoteClientFactory agentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        ObjectProvider<RemoteClientProvider> remoteClientProviders) {
        return new DefaultAgentCoreRemoteClientFactory(properties, remoteDecoratorFactory,
            remoteClientProviders.orderedStream().toList());
    }

    /**
     * Creates the default sandbox client factory when sandbox integration is
     * enabled.
     *
     * @param properties external adapter properties
     * @return default sandbox client factory bean
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.external.sandbox", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(AgentCoreSandboxClientFactory.class)
    public AgentCoreSandboxClientFactory agentCoreSandboxClientFactory(AgentCoreExternalProperties properties) {
        return new DefaultAgentCoreSandboxClientFactory(properties);
    }

    /**
     * Creates the default agent-core-backed service handler.
     *
     * @param agentId agent id configured for the service
     * @param middlewareAdapterRegistrar middleware adapter registrar, if available
     * @param externalSvcAdapterRegistrar external service adapter registrar
     * @return agent-core-backed service handler bean
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnExpression(
        "'${openjiuwen.service.agent-id:}' != '' " + "&& '${openjiuwen.service.handler:agentcore}' == 'agentcore'")
    public AgentHandler coreAgentHandler(@Value("${openjiuwen.service.agent-id}") String agentId,
        @Autowired(required = false) MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
        ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        return new JiuwenCoreAgentHandler(agentId, middlewareAdapterRegistrar, externalSvcAdapterRegistrar);
    }
}
