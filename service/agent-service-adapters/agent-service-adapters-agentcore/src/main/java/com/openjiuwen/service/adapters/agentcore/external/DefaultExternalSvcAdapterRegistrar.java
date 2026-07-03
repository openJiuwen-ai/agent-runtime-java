/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpClientFactory;
import com.openjiuwen.core.foundation.tool.mcp.McpClientProvider;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.foundation.tool.mcp.client.SseClient;
import com.openjiuwen.core.foundation.tool.mcp.client.StdioClient;
import com.openjiuwen.core.foundation.tool.mcp.client.StreamableHttpClient;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.runner.base.Result;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientFactory;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;
import com.openjiuwen.extensions.a2a.A2ARemoteClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

/**
 * Default registrar that installs external MCP and remote client adapters into agent-core.
 *
 * @since 2026-06-24
 */
public class DefaultExternalSvcAdapterRegistrar implements ExternalSvcAdapterRegistrar {
    private static final Logger log = LoggerFactory.getLogger(DefaultExternalSvcAdapterRegistrar.class);

    private final AgentCoreExternalProperties properties;
    private final AgentCoreMcpClientDecoratorFactory decoratorFactory;
    private final AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory;
    private final List<McpClientProvider> customMcpClientProviders;
    private final List<RemoteClientProvider> customRemoteClientProviders;

    public DefaultExternalSvcAdapterRegistrar(
            AgentCoreExternalProperties properties,
            AgentCoreMcpClientDecoratorFactory decoratorFactory) {
        this(properties, decoratorFactory, new DefaultAgentCoreRemoteClientDecoratorFactory());
    }

    public DefaultExternalSvcAdapterRegistrar(
            AgentCoreExternalProperties properties,
            AgentCoreMcpClientDecoratorFactory decoratorFactory,
            AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory) {
        this(properties, decoratorFactory, remoteDecoratorFactory, List.of(), List.of());
    }

    public DefaultExternalSvcAdapterRegistrar(
            AgentCoreExternalProperties properties,
            AgentCoreMcpClientDecoratorFactory decoratorFactory,
            AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
            List<McpClientProvider> customMcpClientProviders,
            List<RemoteClientProvider> customRemoteClientProviders) {
        this.properties = properties != null ? properties : new AgentCoreExternalProperties();
        this.decoratorFactory = decoratorFactory != null
                ? decoratorFactory
                : new DefaultAgentCoreMcpClientDecoratorFactory();
        this.remoteDecoratorFactory = remoteDecoratorFactory != null
                ? remoteDecoratorFactory
                : new DefaultAgentCoreRemoteClientDecoratorFactory();
        this.customMcpClientProviders = customMcpClientProviders != null
                ? List.copyOf(customMcpClientProviders)
                : Collections.emptyList();
        this.customRemoteClientProviders = customRemoteClientProviders != null
                ? List.copyOf(customRemoteClientProviders)
                : Collections.emptyList();
    }

    @Override
    public void registerTo(RunnerConfig runnerConfig) {
        properties.getMcp().validate();
        registerMcpClientProviders();
        registerRemoteClientProviders();
        if (runnerConfig == null) {
            return;
        }
        List<McpServerConfig> merged = new ArrayList<>();
        if (runnerConfig.getMcpServers() != null) {
            merged.addAll(runnerConfig.getMcpServers());
        }
        for (McpServerConfig config : toCoreConfigs()) {
            if (merged.stream().noneMatch(existing -> sameServer(existing, config))) {
                merged.add(config);
            }
        }
        runnerConfig.setMcpServers(merged);
    }

    @Override
    public void registerToRunner() {
        properties.getMcp().validate();
        registerMcpClientProviders();
        registerRemoteClientProviders();
        List<AgentCoreExternalProperties.McpServer> servers = properties.getMcp().getServers();
        if (servers == null || servers.isEmpty()) {
            return;
        }
        for (AgentCoreExternalProperties.McpServer server : servers) {
            McpServerConfig config = toCoreConfig(server);
            List<Result<String>> results = addMcpServer(
                    config,
                    nonBlankText(server.getTag()).orElse(null),
                    server.getExpiryTimeMs());
            for (Result<String> result : results) {
                if (result.isError()) {
                    throw new IllegalStateException(
                            "Failed to register MCP server " + config.getServerName(),
                            result.getError());
                }
            }
            log.info("Registered external MCP server, serverId={}, serverName={}",
                    config.getServerId(), config.getServerName());
        }
    }

    private List<Result<String>> addMcpServer(McpServerConfig config, String tag, Double expiryTimeMs) {
        FutureTask<List<Result<String>>> registrationTask = new FutureTask<>(
                () -> Runner.resourceMgr().addMcpServer(config, tag, expiryTimeMs));
        registrationTask.run();
        try {
            return registrationTask.get();
        } catch (InterruptedException ex) {
            throw new IllegalStateException("Interrupted while registering MCP server " + config.getServerName(), ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to register MCP server " + config.getServerName(), cause);
        }
    }

    private void registerMcpClientProviders() {
        Set<String> customTypes = registerCustomMcpClientProviders();
        if (!customTypes.contains("sse")) {
            registerProvider("sse", SseClient::new);
        }
        if (!customTypes.contains("stdio")) {
            registerProvider("stdio", StdioClient::new);
        }
        if (!customTypes.contains("streamable_http")) {
            registerProvider("streamable_http", StreamableHttpClient::new);
        }
    }

    private void registerRemoteClientProviders() {
        Set<String> customProtocols = registerCustomRemoteClientProviders();
        if (customProtocols.contains("A2A")) {
            return;
        }
        RemoteClientFactory.register("A2A", new RemoteClientProvider() {
            @Override
            public String typeName() {
                return "A2A";
            }

            @Override
            public RemoteClient create(RemoteClientConfig config) {
                RemoteClient delegate = new A2ARemoteClient(config);
                return remoteDecoratorFactory.decorate(config, delegate, properties.policyFor(config));
            }
        });
    }

    private Set<String> registerCustomMcpClientProviders() {
        Set<String> types = new LinkedHashSet<>();
        for (McpClientProvider provider : customMcpClientProviders) {
            if (provider == null || !notBlank(provider.typeName())) {
                continue;
            }
            String type = normalizeClientType(provider.typeName());
            McpClientFactory.register(type, provider);
            types.add(type);
            log.info("Registered custom MCP client provider, type={}", type);
        }
        return types;
    }

    private Set<String> registerCustomRemoteClientProviders() {
        Set<String> protocols = new LinkedHashSet<>();
        for (RemoteClientProvider provider : customRemoteClientProviders) {
            if (provider == null || !notBlank(provider.typeName())) {
                continue;
            }
            String protocol = provider.typeName().toUpperCase(Locale.ROOT);
            RemoteClientFactory.register(protocol, provider);
            protocols.add(protocol);
            log.info("Registered custom remote client provider, protocol={}", protocol);
        }
        return protocols;
    }

    private void registerProvider(String type, Function<McpServerConfig, McpClient> coreFactory) {
        McpClientFactory.register(type, new McpClientProvider() {
            @Override
            public String typeName() {
                return type;
            }

            @Override
            public McpClient create(McpServerConfig config) {
                McpClient delegate = coreFactory.apply(config);
                return decoratorFactory.decorate(config, delegate, properties.policyFor(config));
            }
        });
    }

    private List<McpServerConfig> toCoreConfigs() {
        List<McpServerConfig> configs = new ArrayList<>();
        for (AgentCoreExternalProperties.McpServer server : properties.getMcp().getServers()) {
            configs.add(toCoreConfig(server));
        }
        return configs;
    }

    private McpServerConfig toCoreConfig(AgentCoreExternalProperties.McpServer server) {
        McpServerConfig config = McpServerConfig.builder().build();
        if (notBlank(server.getServerId())) {
            config.setServerId(server.getServerId());
        }
        config.setServerName(requireText(server.getServerName(), "server-name"));
        config.setServerPath(requireText(server.getServerPath(), "server-path"));
        config.setClientType(normalizeClientType(server.getClientType()));
        config.setParams(server.getParams());
        return config;
    }

    private static String normalizeClientType(String clientType) {
        if (!notBlank(clientType)) {
            return "sse";
        }
        String normalized = clientType.toLowerCase(Locale.ROOT);
        if ("streamable-http".equals(normalized)) {
            return "streamable_http";
        }
        return normalized;
    }

    private static String requireText(String value, String property) {
        if (!notBlank(value)) {
            throw new IllegalArgumentException("MCP " + property + " must not be blank");
        }
        return value;
    }

    private static boolean sameServer(McpServerConfig first, McpServerConfig second) {
        return first.getServerId() != null && first.getServerId().equals(second.getServerId());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Optional<String> nonBlankText(String value) {
        return notBlank(value) ? Optional.of(value) : Optional.empty();
    }
}
