/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import com.openjiuwen.service.adapters.versatile.VersatileAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class VersatileAdaptersAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VersatileAdaptersAutoConfiguration.class));

    @Test
    void registersVersatileAgentHandlerWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "openjiuwen.service.handler=versatile",
                        "openjiuwen.service.versatile.base-url=http://localhost:8080")
                .run(context -> assertThat(context.getBean(AgentHandler.class))
                        .isInstanceOf(VersatileAgentHandler.class));
    }

    @Test
    void skipsWhenHandlerMissing() {
        contextRunner
                .withPropertyValues("openjiuwen.service.versatile.base-url=http://localhost:8080")
                .run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
    }

    @Test
    void skipsWhenCustomAgentHandlerBeanPresent() {
        contextRunner
                .withUserConfiguration(CustomHandlerConfig.class)
                .withPropertyValues(
                        "openjiuwen.service.handler=versatile",
                        "openjiuwen.service.versatile.base-url=http://localhost:8080")
                .run(context -> assertThat(context.getBean(AgentHandler.class))
                        .isInstanceOf(CustomHandlerConfig.StubAgentHandler.class));
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
