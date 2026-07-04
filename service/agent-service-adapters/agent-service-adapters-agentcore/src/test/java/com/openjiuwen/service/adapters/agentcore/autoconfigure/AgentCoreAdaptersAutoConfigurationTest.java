/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.ProtocolEnum;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests auto-configuration for agent-core adapter beans and external service
 * properties.
 *
 * @since 2026-06-24
 */
class AgentCoreAdaptersAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(AgentCoreAdaptersAutoConfiguration.class));

    @Test
    void registersJiuwenCoreAgentHandlerWhenAgentIdConfigured() {
        contextRunner.withPropertyValues("openjiuwen.service.agent-id=my-agent")
            .run(context -> assertThat(context.getBean(AgentHandler.class)).isInstanceOf(JiuwenCoreAgentHandler.class));
    }

    @Test
    void skipsWhenCustomAgentHandlerBeanPresent() {
        contextRunner.withUserConfiguration(CustomHandlerConfig.class)
            .withPropertyValues("openjiuwen.service.agent-id=my-agent")
            .run(context -> assertThat(context.getBean(AgentHandler.class)).isInstanceOf(
                CustomHandlerConfig.StubAgentHandler.class));
    }

    @Test
    void skipsWhenAgentIdMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
    }

    @Test
    void registersDefaultExternalSvcAdapterRegistrarAndBindsMcpProperties() {
        contextRunner.withPropertyValues("openjiuwen.service.external.mcp.servers[0].server-id=srv-1",
            "openjiuwen.service.external.mcp.servers[0].server-name=tools",
            "openjiuwen.service.external.mcp.servers[0].server-path=http://localhost:9000/mcp",
            "openjiuwen.service.external.mcp.servers[0].client-type=sse",
            "openjiuwen.service.external.mcp.timeout-ms=2500", "openjiuwen.service.external.mcp.retry.max=2",
            "openjiuwen.service.external.mcp.retry-tool-calls=true",
            "openjiuwen.service.external.remote.timeout-ms=3500", "openjiuwen.service.external.remote.retry.max=1",
            "openjiuwen.service.external.remote.retry-invoke=true",
            "openjiuwen.service.external.remote.clients[0].id=remote-a2a",
            "openjiuwen.service.external.remote.clients[0].name=Remote A2A",
            "openjiuwen.service.external.remote.clients[0].protocol=A2A",
            "openjiuwen.service.external.remote.clients[0].url=http://localhost:18082").run(context -> {
            assertThat(context).hasSingleBean(ExternalSvcAdapterRegistrar.class);
            assertThat(context).hasSingleBean(AgentCoreRemoteClientDecoratorFactory.class);
            assertThat(context.getBean(AgentCoreRemoteClientDecoratorFactory.class)).isInstanceOf(
                DefaultAgentCoreRemoteClientDecoratorFactory.class);
            AgentCoreExternalProperties properties = context.getBean(AgentCoreExternalProperties.class);
            assertThat(properties.getMcp().getTimeoutMs()).isEqualTo(2500);
            assertThat(properties.getMcp().getRetry().getMax()).isEqualTo(2);
            assertThat(properties.getMcp().isRetryToolCalls()).isTrue();
            assertThat(properties.getMcp().getServers()).hasSize(1);
            assertThat(properties.getMcp().getServers().get(0).getServerId()).isEqualTo("srv-1");
            assertThat(properties.getRemote().getTimeoutMs()).isEqualTo(3500);
            assertThat(properties.getRemote().getRetry().getMax()).isEqualTo(1);
            assertThat(properties.getRemote().isRetryInvoke()).isTrue();
            assertThat(properties.getRemote().getClients()).hasSize(1);
            assertThat(properties.getRemote().getClients().get(0).getId()).isEqualTo("remote-a2a");
            assertThat(properties.getRemote().getClients().get(0).getUrl()).isEqualTo("http://localhost:18082");
        });
    }

    @Test
    void emptyExternalConfigurationStartsAndRegistrarDoesNothing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExternalSvcAdapterRegistrar.class);
            RunnerConfig runnerConfig = RunnerConfig.builder().build();

            assertThatCode(() -> context.getBean(ExternalSvcAdapterRegistrar.class)
                .registerTo(runnerConfig)).doesNotThrowAnyException();
            assertThat(runnerConfig.getMcpServers()).isEmpty();
        });
    }

    @Test
    void failsStartupWhenMcpTimeoutOrRetryConfigIsNegative() {
        contextRunner.withPropertyValues("openjiuwen.service.external.mcp.timeout-ms=-1000",
                "openjiuwen.service.external.mcp.retry.max=-2",
                "openjiuwen.service.external.mcp.servers[0].server-id=srv-1",
                "openjiuwen.service.external.mcp.servers[0].server-name=tools",
                "openjiuwen.service.external.mcp.servers[0].server-path=http://localhost:9000/mcp")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void registersRemoteClientFactoryWhenRemoteClientsAreConfigured() {
        contextRunner.withPropertyValues("openjiuwen.service.external.remote.clients[0].id=remote-a2a",
            "openjiuwen.service.external.remote.clients[0].name=Remote A2A",
            "openjiuwen.service.external.remote.clients[0].protocol=A2A",
            "openjiuwen.service.external.remote.clients[0].url=http://localhost:18082",
            "openjiuwen.service.external.remote.clients[0].timeout-ms=1500").run(context -> {
            assertThat(context).hasSingleBean(AgentCoreRemoteClientFactory.class);
            assertThat(context.getBean(AgentCoreRemoteClientFactory.class)).isInstanceOf(
                DefaultAgentCoreRemoteClientFactory.class);
            RemoteClientConfig config = context.getBean(AgentCoreRemoteClientFactory.class).configFor("remote-a2a");
            assertThat(config.getId()).isEqualTo("remote-a2a");
            assertThat(config.getName()).isEqualTo("Remote A2A");
            assertThat(config.getProtocol()).isEqualTo(ProtocolEnum.A2A);
            assertThat(config.getUrl()).isEqualTo("http://localhost:18082");
        });
    }

    @Test
    void doesNotRegisterRemoteClientFactoryWhenRemoteClientsAreMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentCoreRemoteClientFactory.class));
    }

    @Test
    void failsStartupWhenRemoteClientUrlIsInvalid() {
        contextRunner.withPropertyValues("openjiuwen.service.external.remote.clients[0].id=remote-a2a",
                "openjiuwen.service.external.remote.clients[0].protocol=A2A",
                "openjiuwen.service.external.remote.clients[0].url=file:///tmp/a2a")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowsCustomExternalSvcAdapterRegistrarBeanToOverrideDefault() {
        contextRunner.withUserConfiguration(CustomRegistrarConfig.class)
            .run(context -> assertThat(context.getBean(ExternalSvcAdapterRegistrar.class)).isInstanceOf(
                CustomRegistrarConfig.CustomRegistrar.class));
    }

    @Test
    void allowsCustomRemoteClientDecoratorFactoryBeanToOverrideDefault() {
        contextRunner.withUserConfiguration(CustomRemoteDecoratorFactoryConfig.class)
            .run(context -> assertThat(context.getBean(AgentCoreRemoteClientDecoratorFactory.class)).isInstanceOf(
                CustomRemoteDecoratorFactoryConfig.CustomRemoteDecoratorFactory.class));
    }

    @Test
    void skipsSandboxFactoryWhenSandboxIsDisabled() {
        contextRunner.withPropertyValues("openjiuwen.service.external.sandbox.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(AgentCoreSandboxClientFactory.class));
    }

    @Test
    void registersSandboxFactoryWhenSandboxConfigIsValid() {
        contextRunner.withPropertyValues("openjiuwen.service.external.sandbox.enabled=true",
                "openjiuwen.service.external.sandbox.servers[0].server-id=default",
                "openjiuwen.service.external.sandbox.servers[0].service-url=http://localhost:18090",
                "openjiuwen.service.external.sandbox.servers[0].sandbox-type=jiuwenbox",
                "openjiuwen.service.external.sandbox.servers[0].launcher-type=pre_deploy",
                "openjiuwen.service.external.sandbox.timeout-ms=4000", "openjiuwen.service.external.sandbox.retry.max=1")
            .run(context -> {
                assertThat(context).hasSingleBean(AgentCoreSandboxClientFactory.class);
                assertThat(context.getBean(AgentCoreSandboxClientFactory.class)).isInstanceOf(
                    DefaultAgentCoreSandboxClientFactory.class);
                AgentCoreExternalProperties properties = context.getBean(AgentCoreExternalProperties.class);
                assertThat(properties.getSandbox().isEnabled()).isTrue();
                assertThat(properties.getSandbox().getServers()).hasSize(1);
                assertThat(properties.getSandbox().getServers().get(0).getServiceUrl()).isEqualTo(
                    "http://localhost:18090");
                assertThat(properties.getSandbox().getTimeoutMs()).isEqualTo(4000);
                assertThat(properties.getSandbox().getRetry().getMax()).isEqualTo(1);
            });
    }

    @Test
    void failsStartupWhenSandboxIsEnabledWithoutServers() {
        contextRunner.withPropertyValues("openjiuwen.service.external.sandbox.enabled=true")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenSandboxServiceUrlIsInvalid() {
        contextRunner.withPropertyValues("openjiuwen.service.external.sandbox.enabled=true",
                "openjiuwen.service.external.sandbox.servers[0].server-id=default",
                "openjiuwen.service.external.sandbox.servers[0].service-url=ftp://localhost:18090",
                "openjiuwen.service.external.sandbox.servers[0].sandbox-type=jiuwenbox")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowsCustomSandboxClientFactoryBeanToOverrideDefault() {
        contextRunner.withUserConfiguration(CustomSandboxFactoryConfig.class)
            .withPropertyValues("openjiuwen.service.external.sandbox.enabled=true",
                "openjiuwen.service.external.sandbox.servers[0].server-id=default",
                "openjiuwen.service.external.sandbox.servers[0].service-url=http://localhost:18090",
                "openjiuwen.service.external.sandbox.servers[0].sandbox-type=jiuwenbox")
            .run(context -> assertThat(context.getBean(AgentCoreSandboxClientFactory.class)).isInstanceOf(
                CustomSandboxFactoryConfig.CustomSandboxClientFactory.class));
    }

    @Configuration
    static class CustomHandlerConfig {
        @Bean
        AgentHandler customAgentHandler() {
            return new StubAgentHandler();
        }

        static class StubAgentHandler implements AgentHandler {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(com.openjiuwen.service.spec.dto.ServeRequest request,
                com.openjiuwen.service.spec.spi.QueryStreamObserver observer) {
            }
        }
    }

    @Configuration
    static class CustomRegistrarConfig {
        @Bean
        ExternalSvcAdapterRegistrar customExternalSvcAdapterRegistrar() {
            return new CustomRegistrar();
        }

        static class CustomRegistrar implements ExternalSvcAdapterRegistrar {
            @Override
            public void registerTo(com.openjiuwen.core.runner.RunnerConfig runnerConfig) {
            }

            @Override
            public void registerToRunner() {
            }
        }
    }

    @Configuration
    static class CustomRemoteDecoratorFactoryConfig {
        @Bean
        AgentCoreRemoteClientDecoratorFactory customRemoteClientDecoratorFactory() {
            return new CustomRemoteDecoratorFactory();
        }

        static class CustomRemoteDecoratorFactory implements AgentCoreRemoteClientDecoratorFactory {
            @Override
            public RemoteClient decorate(RemoteClientConfig config, RemoteClient delegate,
                AgentCoreExternalProperties.RemotePolicy policy) {
                return delegate;
            }
        }
    }

    @Configuration
    static class CustomSandboxFactoryConfig {
        @Bean
        AgentCoreSandboxClientFactory customSandboxClientFactory() {
            return new CustomSandboxClientFactory();
        }

        static class CustomSandboxClientFactory implements AgentCoreSandboxClientFactory {
            @Override
            public SandboxClient create() {
                return create(null);
            }

            @Override
            public SandboxClient create(String serverId) {
                return new SandboxClient(SandboxGatewayConfig.builder().build());
            }

            @Override
            public SandboxGatewayConfig configFor(String serverId) {
                return SandboxGatewayConfig.builder().build();
            }
        }
    }
}
