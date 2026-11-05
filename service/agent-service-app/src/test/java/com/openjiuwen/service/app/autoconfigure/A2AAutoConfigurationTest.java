/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.config.SpringEnvironmentConfigProvider;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteProjectionHook;
import com.openjiuwen.service.app.controller.a2a.client.RemoteTaskResultHook;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

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

    private final ApplicationContextRunner orchestratorRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
            .withBean(AgentHandler.class, () -> mock(AgentHandler.class))
            .withBean(ActiveStreamRegistry.class, ActiveStreamRegistry::new)
            .withBean(RequestHandler.class, () -> mock(RequestHandler.class));

    @ParameterizedTest
    @MethodSource("duplicateResponseHooks")
    <T> void rejectsDuplicateHooksDespiteMatchingFieldNameOrPrimary(Class<T> hookType, boolean isPrimary) {
        String beanName = hookType == RemoteProjectionHook.class
                ? "remoteProjectionHookProvider" : "remoteTaskResultHookProvider";
        orchestratorRunner.withBean(beanName, hookType, () -> mock(hookType),
                        definition -> definition.setPrimary(isPrimary))
                .withBean("secondHook", hookType, () -> mock(hookType)).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining(hookType.getName());
                });
    }

    private static List<Arguments> duplicateResponseHooks() {
        return List.of(Arguments.of(RemoteProjectionHook.class, false),
                Arguments.of(RemoteProjectionHook.class, true), Arguments.of(RemoteTaskResultHook.class, false),
                Arguments.of(RemoteTaskResultHook.class, true));
    }

    @Test
    void createsDefaultOrchestratorWithoutHooks() {
        orchestratorRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(A2AEnabledServeOrchestrator.class);
        });
    }

    @Test
    void injectsOptionalHooksIntoDefaultOrchestrator() {
        RemoteProjectionHook projection = (event, context) -> null;
        RemoteTaskResultHook result = task -> "custom-result";
        orchestratorRunner.withBean(RemoteProjectionHook.class, () -> projection)
                .withBean(RemoteTaskResultHook.class, () -> result).run(context -> {
                    assertThat(context).hasNotFailed();
                    Object coordinator = ReflectionTestUtils.getField(
                            context.getBean(A2AEnabledServeOrchestrator.class), "batchCoordinator");
                    assertThat(ReflectionTestUtils.getField(coordinator, "projectionHook")).isSameAs(projection);
                    Object mapper = ReflectionTestUtils.getField(coordinator, "batchMapper");
                    assertThat(ReflectionTestUtils.getField(mapper, "taskResultHook")).isSameAs(result);
                });
    }

    @Test
    void injectsOptionalResultHookIntoDefaultCaller() {
        RemoteTaskResultHook hook = task -> "custom-result";
        contextRunner.withBean(RemoteTaskResultHook.class, () -> hook).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(A2ARemoteAgentClient.class), "taskResultHook")).isSameAs(hook);
        });
    }

    @Test
    void internalA2aExecutorsAreNotPublishedByType() {
        contextRunner.run(context -> assertThat(context.getBeansOfType(Executor.class)).isEmpty());
    }

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
    void a2aConfigProviderUsesRuntimeDefaultAndInitializedSdkFallback() {
        contextRunner.run(context -> {
            A2AConfigProvider provider = context.getBean(A2AConfigProvider.class);

            assertThat(provider.getValue(AGENT_TIMEOUT)).isEqualTo("300");
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

    @Test
    void remoteAgentCardRegistryPublishesCatalogChanges() {
        contextRunner.run(context -> {
            List<RemoteAgentCatalogChangedEvent> events = new ArrayList<>();
            context.getSourceApplicationContext().addApplicationListener(event -> {
                if (event instanceof PayloadApplicationEvent<?> payloadEvent
                        && payloadEvent.getPayload() instanceof RemoteAgentCatalogChangedEvent catalogChangedEvent) {
                    events.add(catalogChangedEvent);
                }
            });

            A2ARemoteAgentCardRegistry registry = context.getBean(A2ARemoteAgentCardRegistry.class);
            registry.register("balance", mock(AgentCard.class));

            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.snapshot().version()).isEqualTo(1L);
                assertThat(event.snapshot().entries()).extracting(RemoteAgentEntry::name).containsExactly("balance");
            });
        });
    }
}
