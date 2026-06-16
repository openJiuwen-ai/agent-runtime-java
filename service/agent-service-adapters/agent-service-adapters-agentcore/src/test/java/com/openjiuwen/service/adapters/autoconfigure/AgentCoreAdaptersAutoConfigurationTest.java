/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.autoconfigure;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreAdaptersAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentCoreAdaptersAutoConfiguration.class));

    @Test
    void registersCoreAgentHandlerWhenAgentIdConfigured() {
        contextRunner
                .withPropertyValues("openjiuwen.service.agent-id=my-agent")
                .run(context -> assertThat(context.getBean(AgentHandler.class))
                        .isInstanceOf(CoreAgentHandler.class));
    }

    @Test
    void skipsWhenCustomAgentHandlerBeanPresent() {
        contextRunner
                .withUserConfiguration(CustomHandlerConfig.class)
                .withPropertyValues("openjiuwen.service.agent-id=my-agent")
                .run(context -> assertThat(context.getBean(AgentHandler.class))
                        .isInstanceOf(CustomHandlerConfig.StubAgentHandler.class));
    }

    @Test
    void skipsWhenAgentIdMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
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
}
