/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration.HostedResources;
import com.openjiuwen.service.app.autoconfigure.HostedRuntimeAutoConfiguration;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.app.controller.a2a.A2ATaskContinuation;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleBootstrap;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.HostedSharedLifecycle;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies startup rollback and ordered shutdown within a shared time budget.
 *
 * @since 0.1.2
 */
class HostedLifecycleTest {
    @Test
    void cancelsAllTargetsBeforeWaitingWithOneBudget() {
        var builder = HostedAgentDefinitions.builder();
        List<HostedAgentRuntime> targets = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        addTargets(builder, targets, waits, actions);
        var assembler = mock(HostedRuntimeAssembler.class);
        when(assembler.assemble(anyString(), any())).thenAnswer(invocation -> {
            HostedAgentDefinitions.Entry entry = invocation.getArgument(1);
            return targets.stream().filter(target -> target.agentId().equals(entry.agentId()))
                    .findFirst().orElseThrow();
        });
        var resources = mock(HostedResources.class);
        when(resources.startProcessor(any())).thenAnswer(invocation -> new CompletableFuture<>());
        var shared = mock(HostedSharedLifecycle.class);
        doAnswer(invocation -> {
            assertThat(actions).contains("cleanup:a", "cleanup:b", "cleanup:c", "cleanup:d");
            return null;
        }).when(shared).stop(0L);
        var identity = mock(AgentServiceIdentity.class);
        when(identity.getAppName()).thenReturn("lifecycle-test");
        var properties = new LifecycleProperties();
        properties.setShutdownTimeoutMs(200);
        var definitions = builder.build();
        var catalog = new HostedRuntimeCatalog(definitions);
        var configuration = new HostedLifecycleCoordinator.Configuration(definitions, identity,
                new AgentLifecycleHooks(List.of(), List.of(), List.of()), new DefaultAgentReadiness(), properties,
                List.of(shared), resources, mock(ActiveStreamInterruptor.class));
        var lifecycle = new HostedLifecycleCoordinator(configuration, assembler, catalog);
        lifecycle.runInitPhase();
        lifecycle.runShutdownPhase();
        lifecycle.runShutdownPhase();
        assertThat(waits).hasSize(4);
        assertThat(waits.get(0)).isBetween(0L, 200L);
        assertThat(waits.subList(1, 4)).containsExactly(0L, 0L, 0L);
        targets.forEach(target -> verify(target.handler()).stop());
        verify(shared).stop(0L);
        verify(resources).close();
        assertThatThrownBy(catalog::instances).isInstanceOf(HostedIngressResolver.SelectionException.class);
    }

    private static void addTargets(HostedAgentDefinitions.Builder builder, List<HostedAgentRuntime> targets,
            List<Long> waits, List<String> actions) {
        for (String id : List.of("a", "b", "c", "d")) {
            AgentHandler handler = mock(AgentHandler.class);
            builder.add(id, handler);
            var streams = mock(ActiveStreamRegistry.class);
            doAnswer(invocation -> {
                actions.add("cancel:" + id);
                return null;
            }).when(streams).cancelAll();
            when(streams.awaitDrain(anyLong())).thenAnswer(invocation -> {
                assertThat(actions).contains("cancel:a", "cancel:b", "cancel:c", "cancel:d");
                long remaining = invocation.getArgument(0);
                waits.add(remaining);
                if (waits.size() == 1) {
                    // Consume the actual remaining budget once. Later instances must receive zero.
                    TimeUnit.MILLISECONDS.sleep(remaining + 10);
                }
                return false;
            });
            var execution = new HostedAgentRuntime.Execution(mock(A2AEnabledServeOrchestrator.class), null,
                    null, streams, null, mock(A2ATaskContinuation.class), null,
                    mock(MainEventBusProcessor.class), null, null, null, null);
            targets.add(new HostedAgentRuntime(id, handler, execution, Map.of(),
                    List.of(() -> actions.add("cleanup:" + id))));
        }
    }

    @Test
    void rollbackLogsFailuresAndContinuesOtherCleanup() {
        Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(HostedLifecycleCoordinator.class));
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner().run(context -> {
                var first = context.getBean("first", HostedAssemblyTest.CountingHandler.class);
                var second = context.getBean("second", HostedAssemblyTest.CountingHandler.class);
                first.shouldFailStop = true;
                second.shouldFailStart = true;
                var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
                assertThatThrownBy(lifecycle::runInitPhase).hasRootCauseMessage("start failed");
                lifecycle.runShutdownPhase();
                List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("Hosted agent ")).toList();
                assertThat(messages).containsExactly(
                        "Hosted agent agentId=a operation=start result=begin",
                        "Hosted agent agentId=a operation=start result=success",
                        "Hosted agent agentId=b operation=start result=begin",
                        "Hosted agent agentId=b operation=start result=failure type=IllegalStateException",
                        "Hosted agent agentId=b operation=stop result=begin rollback=true",
                        "Hosted agent agentId=b operation=stop result=success rollback=true",
                        "Hosted agent agentId=a operation=stop result=begin rollback=true",
                        "Hosted agent agentId=a operation=stop result=failure rollback=true"
                                + " type=IllegalStateException");
                assertThat(first.stops).isEqualTo(1);
                assertThat(second.stops).isEqualTo(1);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void springStopsHostedResourcesBeforeBorrowedBeans() {
        runner().withUserConfiguration(CloseConfiguration.class).run(context -> {
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            lifecycle.runInitPhase();
            var probe = context.getBean(DestructionProbe.class);
            var resources = context.getBean(HostedResources.class);
            var first = context.getBean("first", HostedAssemblyTest.CountingHandler.class);
            var second = context.getBean("second", HostedAssemblyTest.CountingHandler.class);
            context.close();
            assertThat(probe.isDestroyed).isTrue();
            assertThat(first.stops).isEqualTo(1);
            assertThat(second.stops).isEqualTo(1);
            assertThat(resources.retryScheduler().isShutdown()).isTrue();
        });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class,
                        HostedRuntimeAutoConfiguration.class))
                .withUserConfiguration(HostedAssemblyTest.Handlers.class)
                .withPropertyValues("spring.application.name=hosted-lifecycle");
    }

    @Configuration(proxyBeanMethods = false)
    static class CloseConfiguration {
        @Bean
        AgentLifecycleBootstrap bootstrap(AgentLifecycleManager lifecycle) {
            return new AgentLifecycleBootstrap(lifecycle);
        }

        @Bean
        DestructionProbe destructionProbe(HostedResources resources, HostedRuntimeCatalog catalog) {
            return new DestructionProbe(resources, catalog);
        }
    }

    static final class DestructionProbe implements DisposableBean {
        private final HostedResources resources;
        private final HostedRuntimeCatalog catalog;
        private boolean isDestroyed;

        DestructionProbe(HostedResources resources, HostedRuntimeCatalog catalog) {
            this.resources = resources;
            this.catalog = catalog;
        }

        @Override
        public void destroy() {
            assertThat(resources.retryScheduler().isShutdown()).isTrue();
            assertThatThrownBy(catalog::instances).isInstanceOf(HostedIngressResolver.SelectionException.class);
            isDestroyed = true;
        }
    }
}
