/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.config.SpringEnvironmentConfigProvider;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Auto-configuration tests for A2A SDK configuration.
 */
class A2AAutoConfigurationTest {
    private static final String AGENT_TIMEOUT = "a2a.blocking.agent.timeout.seconds";

    private static final String CONSUMPTION_TIMEOUT = "a2a.blocking.consumption.timeout.seconds";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
            .withBean(ServeOrchestrator.class, () -> mock(ServeOrchestrator.class))
            .withBean(RequestHandler.class, () -> mock(RequestHandler.class));

    @Test
    void a2aConfigProviderUsesSpringApplicationProperty() {
        contextRunner.withPropertyValues(AGENT_TIMEOUT + "=120").run(context -> {
            A2AConfigProvider provider = context.getBean(A2AConfigProvider.class);

            assertThat(provider).isInstanceOf(SpringEnvironmentConfigProvider.class);
            assertThat(provider.getValue(AGENT_TIMEOUT)).isEqualTo("120");
            assertThat(provider.getOptionalValue(AGENT_TIMEOUT)).contains("120");
        });
    }

    @Test
    void a2aConfigProviderUsesSystemProperty() {
        contextRunner.withSystemProperties(AGENT_TIMEOUT + "=90").run(context -> {
            A2AConfigProvider provider = context.getBean(A2AConfigProvider.class);

            assertThat(provider.getValue(AGENT_TIMEOUT)).isEqualTo("90");
        });
    }

    @Test
    void a2aConfigProviderFallsBackToInitializedSdkDefaults() {
        contextRunner.run(context -> {
            A2AConfigProvider provider = context.getBean(A2AConfigProvider.class);

            assertThat(provider.getValue(AGENT_TIMEOUT)).isEqualTo("30");
            assertThat(provider.getOptionalValue(CONSUMPTION_TIMEOUT)).contains("5");
        });
    }

    @Test
    void a2aConfigProviderAllowsCustomProviderOverride() {
        A2AConfigProvider customProvider = mock(A2AConfigProvider.class);

        contextRunner.withBean(A2AConfigProvider.class, () -> customProvider).run(context -> {
            assertThat(context).hasSingleBean(A2AConfigProvider.class);
            assertThat(context.getBean(A2AConfigProvider.class)).isSameAs(customProvider);
        });
    }
}
