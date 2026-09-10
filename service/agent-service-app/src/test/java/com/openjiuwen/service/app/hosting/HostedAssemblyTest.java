/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.HostedRuntimeAutoConfiguration;
import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.HostedSharedLifecycle;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class HostedAssemblyTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class, A2AAutoConfiguration.class))
            .withUserConfiguration(Handlers.class).withPropertyValues("spring.application.name=hosted-test");

    @Test
    void preservesHandlerBeansAndBindsAllTargetStateBeforeAtomicPublication() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var catalog = context.getBean(HostedRuntimeCatalog.class);
            assertThatThrownBy(catalog::instances).isInstanceOf(HostedIngressResolver.SelectionException.class);
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            lifecycle.runInitPhase();
            assertThat(catalog.instances()).extracting(HostedAgentRuntime::agentId).containsExactly("a", "b");
            var a = catalog.resolve("a");
            var b = catalog.resolve("b");
            assertThat(a.handler()).isSameAs(context.getBean("first"));
            assertThat(b.handler()).isSameAs(context.getBean("second"));
            assertThat(a.taskStore()).isNotSameAs(b.taskStore());
            assertThat(a.execution().agentExecutor()).isNotSameAs(b.execution().agentExecutor());
            assertThat(a.execution().queueManager()).isNotSameAs(b.execution().queueManager());
            assertThat(a.execution().eventProcessor()).isNotSameAs(b.execution().eventProcessor());
            assertThat(a.execution().pushConfigStore()).isNotSameAs(b.execution().pushConfigStore());
            Task task = Task.builder().id("same-task").contextId("same-conversation")
                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
            b.taskStore().save(task, true);
            assertThat(a.taskStore().get("same-task")).isNull();
            assertThat(context.getBean(TaskStore.class).get("same-task")).isSameAs(task);
            assertThat(context.getBean(RequestHandler.class)).isNotNull();
            lifecycle.runInitPhase();
            assertThat(context.getBean("first", CountingHandler.class).starts).isEqualTo(1);
            lifecycle.runShutdownPhase();
            lifecycle.runShutdownPhase();
            assertThat(context.getBean("first", CountingHandler.class).stops).isEqualTo(1);
            assertThat(context.getBean("second", CountingHandler.class).stops).isEqualTo(1);
            assertThatThrownBy(catalog::instances).isInstanceOf(HostedIngressResolver.SelectionException.class);
        });
    }

    @Test
    void rollsBackAllStartedHandlersAndSharedResourcesWhenLaterHandlerFails() {
        var shared = new CountingSharedLifecycle();
        runner.withBean(HostedSharedLifecycle.class, () -> shared).run(context -> {
            var first = context.getBean("first", CountingHandler.class);
            var second = context.getBean("second", CountingHandler.class);
            second.failStart = true;
            first.failStop = true;
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            assertThatThrownBy(lifecycle::runInitPhase).hasRootCauseMessage("start failed");
            assertThat(first.starts).isEqualTo(1);
            assertThat(second.starts).isEqualTo(1);
            assertThat(first.stops).isEqualTo(1);
            assertThat(second.stops).isEqualTo(1);
            assertThat(shared.starts).isEqualTo(1);
            assertThat(shared.stops).isEqualTo(1);
            assertThatThrownBy(context.getBean(HostedRuntimeCatalog.class)::instances)
                    .isInstanceOf(HostedIngressResolver.SelectionException.class);
            lifecycle.runShutdownPhase();
            assertThat(shared.stops).isEqualTo(1);
        });
    }

    @Test
    void stopFailureInOneHandlerDoesNotSkipOtherHandlersOrSharedResources() {
        var shared = new CountingSharedLifecycle();
        runner.withBean(HostedSharedLifecycle.class, () -> shared).run(context -> {
            var first = context.getBean("first", CountingHandler.class);
            var second = context.getBean("second", CountingHandler.class);
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            lifecycle.runInitPhase();
            second.failStop = true;
            lifecycle.runShutdownPhase();
            assertThat(first.stops).isEqualTo(1);
            assertThat(second.stops).isEqualTo(1);
            assertThat(shared.stops).isEqualTo(1);
        });
    }

    @Test
    void cancellingSameConversationInOneInstancePreservesOthersUntilProcessShutdown() {
        runner.run(context -> {
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            lifecycle.runInitPhase();
            var catalog = context.getBean(HostedRuntimeCatalog.class);
            var a = catalog.resolve("a");
            var b = catalog.resolve("b");
            var handleA = a.streams().register("same-conversation");
            var handleB = b.streams().register("same-conversation");
            a.streams().cancel("same-conversation");
            assertThat(handleA.isCancelled()).isTrue();
            assertThat(handleB.isCancelled()).isFalse();
            assertThat(b.streams().activeCount()).isEqualTo(1);
            var anotherA = a.streams().register("another-conversation");
            lifecycle.runShutdownPhase();
            assertThat(anotherA.isCancelled()).isTrue();
            assertThat(handleB.isCancelled()).isTrue();
            // Cancellation preserves the existing signal contract, not proof of business-thread exit.
            assertThat(context.getBean("first", CountingHandler.class).stops).isEqualTo(1);
            assertThat(context.getBean("second", CountingHandler.class).stops).isEqualTo(1);
        });
    }

    @Test
    void boundRemoteCallerChangesOnlyAlreadySelectedPushAddressAndKeepsPartsAndCorrelation() {
        List<RemoteCall> sent = new ArrayList<>();
        RemoteAgentCaller transport = (call, observer) -> {
            sent.add(call);
            return CompletableFuture.completedFuture(new RemoteCallOutcome("remote-task",
                    TaskState.TASK_STATE_COMPLETED, "COMPLETED", "done", null));
        };
        runner.withPropertyValues("openjiuwen.service.a2a.public-url=https://runtime.example/prefix")
                .withBean(RemoteAgentCaller.class, () -> transport)
                .withBean(HostedRuntimeAssembler.Extension.class, CapturedCaller::new).run(context -> {
                    context.getBean(HostedLifecycleCoordinator.class).runInitPhase();
                    var catalog = context.getBean(HostedRuntimeCatalog.class);
                    for (String id : List.of("a", "b")) {
                        var caller = catalog.resolve(id).extension(RemoteAgentCaller.class).orElseThrow();
                        RemoteCall plain = new RemoteCall("remote", "hello", "original-context", "original-task",
                                Map.of("business", "value"));
                        caller.callOutcome(plain, null).join();
                        assertThat(sent.get(sent.size() - 1)).isSameAs(plain);
                        var parts = List.<Map<String, Object>>of(Map.of("kind", "data", "data", Map.of("count", 3)));
                        var metadata = Map.<String, Object>of("runtime.a2a.callbackUrl", "https://old.example/callback",
                                "runtime.a2a.callbackId", "original-id", "runtime.a2a.callbackToken", "test-token",
                                "business", "value");
                        var push = new RemoteCall("remote", "hello", "original-context", "original-task", metadata,
                                Map.of("message-marker", "unchanged"), true, parts);
                        caller.callOutcome(push, null).join();
                        RemoteCall actual = sent.get(sent.size() - 1);
                        assertThat(actual).isEqualTo(new RemoteCall("remote", "hello", "original-context", "original-task",
                                Map.of("runtime.a2a.callbackUrl", "https://runtime.example/prefix/a2a/push-notifications/callback/" + id,
                                        "runtime.a2a.callbackId", "original-id", "runtime.a2a.callbackToken", "test-token",
                                        "business", "value"), push.messageMetadata(), true, parts));
                        assertThat(push.metadata()).isEqualTo(metadata);
                    }
                });
    }

    static final class CapturedCaller implements HostedRuntimeAssembler.Extension {
        @Override
        public RemoteAgentCaller decorateRemoteCaller(HostedRuntimeAssembler.Assembly assembly, RemoteAgentCaller caller) {
            assembly.bind(RemoteAgentCaller.class, caller);
            return caller;
        }
    }

    @Test
    void cardSubtreeRejectsInfrastructureOverridesAndMisspellings() {
        runner.withPropertyValues("openjiuwen.service.a2a.agents.a.agent-threads=8").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("agent-threads");
        });
    }

    @Test
    void rejectsUnscopedGlobalTaskStoreInsteadOfSilentlyIgnoringIt() {
        runner.withBean("customTaskStore", TaskStore.class, InMemoryTaskStore::new).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Unscoped TaskStore replacement");
        });
    }

    @Test
    void rejectsLegacyExecutionRootInHostedMode() {
        runner.withPropertyValues("openjiuwen.service.agent-id=legacy").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("conflicts with HostedAgentDefinitions");
        });
    }

    @Test
    void rejectsAdditionalDefinitionsAndUnregisteredHandlers() {
        runner.withBean("otherDefinitions", HostedAgentDefinitions.class,
                () -> HostedAgentDefinitions.builder().add("other", new CountingHandler()).build()).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("HostedAgentDefinitions");
                });
        runner.withBean("unregistered", AgentHandler.class, CountingHandler::new).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Unregistered AgentHandler");
        });
    }

    @Test
    void rejectsPrimaryHandlerAndGlobalExecutionReplacements() {
        runner.withInitializer(context -> context.addBeanFactoryPostProcessor(
                factory -> factory.getBeanDefinition("first").setPrimary(true))).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("must use qualifiers");
                });
        runner.withBean("unscopedOrchestrator", ServeOrchestrator.class,
                () -> org.mockito.Mockito.mock(ServeOrchestrator.class)).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Unscoped ServeOrchestrator");
                });
        runner.withBean("unscopedSdk", RequestHandler.class,
                () -> org.mockito.Mockito.mock(RequestHandler.class)).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("Unscoped RequestHandler");
                });
    }

    @Test
    void redisWithoutClientFailsBeforePublicationAndStartsNoHandler() {
        var properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        runner.withBean(MiddlewareProperties.class, () -> properties).run(context -> {
            assertThat(context).hasNotFailed();
            var lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            assertThatThrownBy(lifecycle::runInitPhase)
                    .hasRootCauseMessage("RuntimeRedisClient is required for redis A2A task store");
            assertThat(context.getBean("first", CountingHandler.class).starts).isZero();
            assertThat(context.getBean("second", CountingHandler.class).starts).isZero();
            assertThatThrownBy(context.getBean(HostedRuntimeCatalog.class)::instances)
                    .isInstanceOf(HostedIngressResolver.SelectionException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean(destroyMethod = "")
        CountingHandler first() {
            return new CountingHandler();
        }

        @Bean(destroyMethod = "")
        CountingHandler second() {
            return new CountingHandler();
        }

        @Bean
        HostedAgentDefinitions definitions(@Qualifier("first") AgentHandler first,
                @Qualifier("second") AgentHandler second) {
            return HostedAgentDefinitions.builder().add("a", first).add("b", second).defaultAgent("b").build();
        }

        @Bean
        AgentServiceIdentity identity(Environment environment) {
            return new DefaultAgentServiceIdentity(environment);
        }

        @Bean
        DefaultAgentReadiness readiness() {
            return new DefaultAgentReadiness();
        }

        @Bean
        AgentLifecycleHooks hooks() {
            return new AgentLifecycleHooks(List.of(), List.of(), List.of());
        }

        @Bean
        ActiveStreamInterruptor interruptor(ObjectProvider<ServeOrchestrator> orchestrator) {
            return new ActiveStreamInterruptor(orchestrator, List.of());
        }
    }

    static class CountingHandler implements AgentHandler {
        int starts;
        int stops;
        boolean failStart;
        boolean failStop;

        @Override
        public void start() {
            starts++;
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
        }

        @Override
        public void stop() {
            stops++;
            if (failStop) {
                throw new IllegalStateException("stop failed");
            }
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse();
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onComplete();
        }
    }

    static class CountingSharedLifecycle implements HostedSharedLifecycle {
        int starts;
        int stops;

        @Override
        public void prepare(String applicationName, HostedAgentDefinitions definitions) {
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
