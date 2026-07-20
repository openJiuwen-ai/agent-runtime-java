/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpClientFactory;
import com.openjiuwen.core.foundation.tool.mcp.McpClientProvider;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.ProtocolEnum;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientFactory;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests external service adapter registration into agent-core extension points.
 *
 * @since 2026-06-24
 */
class DefaultExternalSvcAdapterRegistrarTest {
    @Test
    void regToRunConfAddsConfMcpServersAndNorTranName() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        AgentCoreExternalProperties.McpServer server = new AgentCoreExternalProperties.McpServer();
        server.setServerId("srv-1");
        server.setServerName("tools");
        server.setServerPath("http://localhost:9000/mcp");
        server.setClientType("streamable-http");
        properties.getMcp().setServers(List.of(server));

        RunnerConfig runnerConfig = RunnerConfig.builder().build();
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory());

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            registrar.registerTo(runnerConfig);
        }

        assertThat(runnerConfig.getMcpServers()).hasSize(1);
        McpServerConfig coreConfig = runnerConfig.getMcpServers().get(0);
        assertThat(coreConfig.getServerId()).isEqualTo("srv-1");
        assertThat(coreConfig.getServerName()).isEqualTo("tools");
        assertThat(coreConfig.getServerPath()).isEqualTo("http://localhost:9000/mcp");
        assertThat(coreConfig.getClientType()).isEqualTo("streamable_http");
    }

    @Test
    void constructorDoesNotValidateMcpServersBeforeRegistration() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getMcp().setServers(List.of(new AgentCoreExternalProperties.McpServer()));

        assertThatCode(() -> new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory())).doesNotThrowAnyException();
    }

    @Test
    void registerToRunnerConfigRejectsInvalidMcpServerConfig() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getMcp().setServers(List.of(new AgentCoreExternalProperties.McpServer()));
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory());

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            assertThatThrownBy(() -> registrar.registerTo(RunnerConfig.builder().build())).isInstanceOf(
                IllegalArgumentException.class).hasMessageContaining("MCP server-name");
        }
    }

    @Test
    void registerToRunnerConfigAddsMultipleConfiguredMcpServers() {
        AgentCoreExternalProperties.McpServer firstServer = new AgentCoreExternalProperties.McpServer();
        firstServer.setServerId("srv-1");
        firstServer.setServerName("tools-a");
        firstServer.setServerPath("http://localhost:9001/mcp");
        firstServer.setClientType("sse");
        firstServer.setParams(Map.of("tenant", "a"));
        AgentCoreExternalProperties.McpServer secondServer = new AgentCoreExternalProperties.McpServer();
        secondServer.setServerId("srv-2");
        secondServer.setServerName("tools-b");
        secondServer.setServerPath("http://localhost:9002/mcp");
        secondServer.setClientType("streamable-http");
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getMcp().setServers(List.of(firstServer, secondServer));
        RunnerConfig runnerConfig = RunnerConfig.builder().build();
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory());

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            registrar.registerTo(runnerConfig);
        }

        assertThat(runnerConfig.getMcpServers()).hasSize(2);
        assertThat(runnerConfig.getMcpServers()).extracting(McpServerConfig::getServerId)
            .containsExactly("srv-1", "srv-2");
        assertThat(runnerConfig.getMcpServers()).extracting(McpServerConfig::getClientType)
            .containsExactly("sse", "streamable_http");
        assertThat(runnerConfig.getMcpServers().get(0).getParams()).containsEntry("tenant", "a");
    }

    @Test
    void mcpServerSpecificTimeoutOverridesGlobalTimeout() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getMcp().setTimeoutMs(3000);
        AgentCoreExternalProperties.McpServer fastServer = new AgentCoreExternalProperties.McpServer();
        fastServer.setServerId("fast");
        fastServer.setServerName("fast-tools");
        fastServer.setServerPath("http://localhost:9001/mcp");
        fastServer.setTimeoutMs(1000);
        AgentCoreExternalProperties.McpServer defaultServer = new AgentCoreExternalProperties.McpServer();
        defaultServer.setServerId("default");
        defaultServer.setServerName("default-tools");
        defaultServer.setServerPath("http://localhost:9002/mcp");
        properties.getMcp().setServers(List.of(fastServer, defaultServer));

        AgentCoreExternalProperties.McpPolicy fastPolicy = properties.policyFor(
            McpServerConfig.builder().serverId("fast").serverName("fast-tools").build());
        AgentCoreExternalProperties.McpPolicy defaultPolicy = properties.policyFor(
            McpServerConfig.builder().serverId("default").serverName("default-tools").build());

        assertThat(fastPolicy.getTimeoutMs()).isEqualTo(1000);
        assertThat(defaultPolicy.getTimeoutMs()).isEqualTo(3000);
    }

    @Test
    void mcpServerPropertiesExposeTlsAndAuthConfiguration() {
        AgentCoreExternalProperties.McpServer server = new AgentCoreExternalProperties.McpServer();
        server.getAuth().setType("bearer");
        server.getTls().setEnabled(true);

        assertThat(server.getAuth().getType()).isEqualTo("bearer");
        assertThat(server.getTls().isEnabled()).isTrue();
    }

    @Test
    void registerToRunnerRegistersDecoratedA2ARemoteProviderOnly() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getRemote().setTimeoutMs(4500);
        RecordingRemoteDecoratorFactory remoteDecoratorFactory = new RecordingRemoteDecoratorFactory();
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory(), remoteDecoratorFactory);

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            registrar.registerToRunner();

            RemoteClient a2aClient = RemoteClientFactory.create(RemoteClientConfig.builder()
                .id("remote-a2a")
                .protocol(ProtocolEnum.A2A)
                .url("http://localhost:18081/a2a")
                .build());
            RemoteClient mqClient = RemoteClientFactory.create(
                RemoteClientConfig.builder().id("remote-mq").protocol(ProtocolEnum.MQ).topic("agent-topic").build());

            assertThat(a2aClient).isInstanceOf(RecordingRemoteDecoratorFactory.MarkerRemoteClient.class);
            assertThat(mqClient).isNotInstanceOf(RecordingRemoteDecoratorFactory.MarkerRemoteClient.class);
            assertThat(remoteDecoratorFactory.seenConfig.getId()).isEqualTo("remote-a2a");
            assertThat(remoteDecoratorFactory.seenPolicy.getTimeoutMs()).isEqualTo(4500);
        }
    }

    @Test
    void customMcpClientProvDefProvForSameTransType() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory(), new DefaultAgentCoreRemoteClientDecoratorFactory(),
            List.of(new CustomMcpClientProvider()), List.of());

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            registrar.registerToRunner();

            McpClient client = McpClientFactory.create(McpServerConfig.builder()
                .serverId("custom-sse")
                .serverName("custom")
                .serverPath("http://localhost/mcp")
                .clientType("sse")
                .build());

            assertThat(client).isInstanceOf(CustomMcpClient.class);
        }
    }

    @Test
    void customRemoteClientProvDefProvForSameProtocol() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory(), new DefaultAgentCoreRemoteClientDecoratorFactory(),
            List.of(), List.of(new CustomA2ARemoteClientProvider()));

        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            registrar.registerToRunner();

            RemoteClient client = RemoteClientFactory.create(RemoteClientConfig.builder()
                .id("custom-a2a")
                .protocol(ProtocolEnum.A2A)
                .url("http://localhost:18081/a2a")
                .build());

            assertThat(client).isInstanceOf(CustomRemoteClient.class);
        }
    }

    private static final class RecordingRemoteDecoratorFactory implements AgentCoreRemoteClientDecoratorFactory {
        private RemoteClientConfig seenConfig;

        private AgentCoreExternalProperties.RemotePolicy seenPolicy;

        @Override
        public RemoteClient decorate(RemoteClientConfig config, RemoteClient delegate,
            AgentCoreExternalProperties.RemotePolicy policy) {
            this.seenConfig = config;
            this.seenPolicy = policy;
            return new MarkerRemoteClient(delegate);
        }

        private record MarkerRemoteClient(RemoteClient delegate) implements RemoteClient {
            @Override
            public void start() {
                delegate.start();
            }

            @Override
            public void stop() {
                delegate.stop();
            }

            @Override
            public boolean isStarted() {
                return delegate.isStarted();
            }

            @Override
            public Object invoke(java.util.Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
                return delegate.invoke(inputs, timeoutSeconds);
            }

            @Override
            public java.util.Iterator<Object> stream(java.util.Map<String, Object> inputs, Double timeoutSeconds)
                throws Exception {
                return delegate.stream(inputs, timeoutSeconds);
            }
        }
    }

    private static final class CustomMcpClientProvider implements McpClientProvider {
        @Override
        public String typeName() {
            return "sse";
        }

        @Override
        public McpClient create(McpServerConfig config) {
            return new CustomMcpClient();
        }
    }

    private static final class CustomMcpClient implements McpClient {
        @Override
        public boolean connect(int retryTimes, float timeout) {
            return true;
        }

        @Override
        public boolean disconnect(float timeout) {
            return true;
        }

        @Override
        public java.util.List<Object> listTools(float timeout) {
            return java.util.List.of();
        }

        @Override
        public Object callTool(String toolName, Map<String, Object> arguments, float timeout) {
            return null;
        }

        @Override
        public Optional<Object> getToolInfo(String toolName, float timeout) {
            return Optional.empty();
        }

        @Override
        public String getServerPath() {
            return "http://localhost/mcp";
        }
    }

    private static final class CustomA2ARemoteClientProvider implements RemoteClientProvider {
        @Override
        public String typeName() {
            return "A2A";
        }

        @Override
        public RemoteClient create(RemoteClientConfig config) {
            return new CustomRemoteClient();
        }
    }

    private static final class CustomRemoteClient implements RemoteClient {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isStarted() {
            return false;
        }

        @Override
        public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) {
            return Map.of();
        }

        @Override
        public java.util.Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
            return java.util.List.<Object>of().iterator();
        }
    }
}
