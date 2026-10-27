/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.foundation.tool.mcp.McpClientProvider;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;
import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.PersistenceCheckpointer;
import com.openjiuwen.core.session.checkpointer.step.KvStepJournal;
import com.openjiuwen.core.session.checkpointer.step.StepJournal;
import com.openjiuwen.core.session.checkpointer.step.StepRecoveryHook;
import com.openjiuwen.service.adapters.agentcore.agentfw.CoreRunnerLifecycleCoordinator;
import com.openjiuwen.service.adapters.agentcore.agentfw.DeadStepSweeper;
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
import com.openjiuwen.service.adapters.common.autoconfigure.ExternalSecurityAutoConfiguration;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for agent-core external service adapters.
 *
 * @since 2026-06-24
 */
@AutoConfiguration
@Import(ExternalSecurityAutoConfiguration.class)
@EnableConfigurationProperties(AgentCoreExternalProperties.class)
public class AgentCoreAdaptersAutoConfiguration {
    /**
     * The dead-step sweeper interval in seconds; a fixed default keeps the
     * surface small until a tuning need shows up.
     */
    private static final long STEP_SWEEPER_INTERVAL_SECONDS = 60L;

    /**
     * Default step-journal retention in millis: 7 days, aligned with the
     * checkpointer TTL default.
     */
    private static final long DEFAULT_STEP_JOURNAL_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;

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
     * @param outboundSecuritySupport outboundSecuritySupport
     * @return external service adapter registrar bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalSvcAdapterRegistrar.class)
    public ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar(AgentCoreExternalProperties properties,
        AgentCoreMcpClientDecoratorFactory decoratorFactory,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        ObjectProvider<McpClientProvider> mcpClientProviders,
        ObjectProvider<RemoteClientProvider> remoteClientProviders,
        ExternalOutboundSecuritySupport outboundSecuritySupport) {
        return new DefaultExternalSvcAdapterRegistrar(properties, decoratorFactory, remoteDecoratorFactory,
            mcpClientProviders.orderedStream().toList(), remoteClientProviders.orderedStream().toList(),
            outboundSecuritySupport);
    }

    /**
     * Creates the default remote client factory when configured remote clients
     * exist.
     *
     * @param properties external adapter properties
     * @param remoteDecoratorFactory remote client decorator factory
     * @param remoteClientProviders custom remote client providers
     * @param outboundSecuritySupport outboundSecuritySupport
     * @return default remote client factory bean
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.external.remote.clients[0]", name = "id")
    @ConditionalOnMissingBean(AgentCoreRemoteClientFactory.class)
    public AgentCoreRemoteClientFactory agentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        ObjectProvider<RemoteClientProvider> remoteClientProviders,
        ExternalOutboundSecuritySupport outboundSecuritySupport) {
        return new DefaultAgentCoreRemoteClientFactory(properties, remoteDecoratorFactory,
            remoteClientProviders.orderedStream().toList(), outboundSecuritySupport);
    }

    /**
     * Creates the default sandbox client factory when sandbox integration is
     * enabled.
     *
     * @param properties external adapter properties
     * @param outboundSecuritySupport outboundSecuritySupport
     * @return default sandbox client factory bean
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.external.sandbox", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(AgentCoreSandboxClientFactory.class)
    public AgentCoreSandboxClientFactory agentCoreSandboxClientFactory(AgentCoreExternalProperties properties,
        ExternalOutboundSecuritySupport outboundSecuritySupport) {
        return new DefaultAgentCoreSandboxClientFactory(properties, outboundSecuritySupport);
    }

    /**
     * Creates the default agent-core-backed service handler.
     *
     * @param agentId agent id configured for the service
     * @param middlewareAdapterRegistrar middleware adapter registrar, if available
     * @param externalSvcAdapterRegistrar external service adapter registrar
     * @param modelCatalog deployment model catalog, if request model selection is enabled
     * @return agent-core-backed service handler bean
     */
    @Bean
    @ConditionalOnMissingBean({AgentHandler.class, HostedAgentDefinitions.class})
    @ConditionalOnExpression(
        "'${openjiuwen.service.agent-id:}' != '' " + "&& '${openjiuwen.service.handler:agentcore}' == 'agentcore'")
    public AgentHandler coreAgentHandler(@Value("${openjiuwen.service.agent-id}") String agentId,
        @Autowired(required = false) MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
        ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar,
        ObjectProvider<LlmModelCatalog> modelCatalog) {
        return new JiuwenCoreAgentHandler(agentId, middlewareAdapterRegistrar, externalSvcAdapterRegistrar,
                agentId, modelCatalog.getIfAvailable());
    }

    /**
     * Creates the shared Core Runner lifecycle for hosted handlers.
     *
     * @param middleware optional middleware registrar
     * @param external external service adapter registrar
     * @return shared Runner lifecycle coordinator
     */
    @Bean
    @ConditionalOnBean(HostedAgentDefinitions.class)
    @ConditionalOnMissingBean(CoreRunnerLifecycleCoordinator.class)
    public CoreRunnerLifecycleCoordinator hostedCoreRunnerLifecycle(
            ObjectProvider<MiddlewareAdapterRegistrar> middleware,
            ExternalSvcAdapterRegistrar external) {
        return new CoreRunnerLifecycleCoordinator(middleware.getIfAvailable(), external);
    }

    /**
     * Creates the dead-step sweeper when step recovery is explicitly enabled.
     *
     * <p>The sweeper scans the checkpointer for RUNNING step cursors left by
     * crashed processes and walks the shared recovery pipeline (hook first,
     * markLost fallback). When the checkpointer is persistence-backed, the
     * sweep pass also purges step-journal records older than the retention
     * deadline ({@code openjiuwen.step-journal.retention-seconds}, default 7
     * days aligned with the checkpointer TTL; non-positive disables the
     * purge). Off by default; enable via
     * {@code openjiuwen.step-sweeper.enabled=true}.</p>
     *
     * @param checkpointer checkpointer bean, required for the scan
     * @param hook optional business recovery hook
     * @param journalRetentionSeconds configured journal retention in seconds
     * @return started sweeper; the bean is absent entirely when disabled
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "openjiuwen.step-sweeper", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeadStepSweeper.class)
    public DeadStepSweeper deadStepSweeper(Checkpointer checkpointer,
            @Autowired(required = false) StepRecoveryHook hook,
            @Value("${openjiuwen.step-journal.retention-seconds:604800}") long journalRetentionSeconds) {
        StepJournal journal = checkpointer instanceof PersistenceCheckpointer persistence
                ? new KvStepJournal(persistence.kvStore())
                : null;
        DeadStepSweeper sweeper = new DeadStepSweeper(checkpointer, hook, STEP_SWEEPER_INTERVAL_SECONDS,
                journal, journalRetentionSeconds * 1000L);
        sweeper.start();
        return sweeper;
    }
}
