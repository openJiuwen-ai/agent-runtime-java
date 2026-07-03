/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DefaultAgentLifecycleManagerTest
 *
 * @since 2026-07-03
 */
class DefaultAgentLifecycleManagerTest {

    @Test
    void initMarksAgentLoadedWhenHandlerBeanPresent() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        AgentHandler handler = stubAgentHandler();

        DefaultAgentLifecycleManager manager = newManager(readiness, handler, List.of(), List.of(), List.of());

        manager.runInitPhase();

        assertThat(readiness.isAgentLoaded()).isTrue();
    }

    @Test
    void initRunsHooksWhenHandlerBeanPresent() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        AgentHandler handler = stubAgentHandler();
        AtomicInteger hookRunCount = new AtomicInteger();

        AgentInitHook hook = context -> hookRunCount.incrementAndGet();

        DefaultAgentLifecycleManager manager = newManager(readiness, handler, List.of(hook), List.of(), List.of());

        manager.runInitPhase();

        assertThat(hookRunCount.get()).isEqualTo(1);
        assertThat(readiness.isAgentLoaded()).isTrue();
    }

    @Test
    void initRunsHooksInOrderAndMarksAgentLoadedWhenHandlerPresent() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        List<String> order = new ArrayList<>();
        AgentInitHook first = new OrderedInitHook(order, 1, "first");
        AgentInitHook second = new OrderedInitHook(order, 2, "second");

        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(first, second),
                List.of(), List.of());

        manager.runInitPhase();

        assertThat(order).containsExactly("first", "second");
        assertThat(readiness.isAgentLoaded()).isTrue();
    }

    @Test
    void initFailFastPreventsAgentLoadedOnHookFailure() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        AgentInitHook failing = context -> {
            throw new IllegalStateException("init failed");
        };
        LifecycleProperties properties = new LifecycleProperties();
        properties.setInitFailFast(true);

        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(failing), List.of(),
                List.of(), properties);

        assertThatThrownBy(manager::runInitPhase).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent init phase failed");
        assertThat(readiness.isAgentLoaded()).isFalse();
    }

    @Test
    void initFailureWithFailFastDisabledKeepsProcessUpAndAgentNotLoaded() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        AgentInitHook failing = context -> {
            throw new IllegalStateException("init failed");
        };
        LifecycleProperties properties = new LifecycleProperties();
        properties.setInitFailFast(false);

        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(failing), List.of(),
                List.of(), properties);

        manager.runInitPhase();

        assertThat(readiness.isProcessUp()).isTrue();
        assertThat(readiness.isAgentLoaded()).isFalse();
    }

    @Test
    void shutdownRunsHooksInReverseOrderAndClearsReadiness() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        List<String> order = new ArrayList<>();
        AgentShutdownHook first = context -> order.add("first");
        AgentShutdownHook second = context -> order.add("second");

        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(),
                List.of(first, second), List.of());
        manager.runInitPhase();

        manager.runShutdownPhase();

        assertThat(order).containsExactly("second", "first");
        assertThat(readiness.isAgentLoaded()).isFalse();
        assertThat(readiness.isProcessUp()).isFalse();
    }

    @Test
    void initFailFastFalseDoesNotThrowAndKeepsAgentNotLoaded() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        AgentInitHook failing = context -> {
            throw new IllegalStateException("init failed");
        };
        LifecycleProperties properties = new LifecycleProperties();
        properties.setInitFailFast(false);

        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(failing), List.of(),
                List.of(), properties);

        manager.runInitPhase();

        assertThat(readiness.isAgentLoaded()).isFalse();
    }

    @Test
    void fullInitShutdownWorkflowRunsHooksAndHandlerLifecycle() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean startCalled = new AtomicBoolean(false);
        AtomicBoolean stopCalled = new AtomicBoolean(false);
        List<String> initOrder = new ArrayList<>();
        List<String> shutdownOrder = new ArrayList<>();

        AgentHandler handler = new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                    com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(com.openjiuwen.service.spec.dto.ServeRequest request,
                    com.openjiuwen.service.spec.spi.QueryStreamObserver observer) {
            }

            @Override
            public void start() {
                startCalled.set(true);
            }

            @Override
            public void stop() {
                stopCalled.set(true);
            }
        };

        DefaultAgentLifecycleManager manager = newManager(readiness, handler, List.of(context -> initOrder.add("init")),
                List.of(context -> shutdownOrder.add("shutdown")), List.of(), new LifecycleProperties(), registry,
                null);

        manager.runInitPhase();
        assertThat(initOrder).containsExactly("init");
        assertThat(startCalled.get()).isTrue();
        assertThat(readiness.isAgentLoaded()).isTrue();

        registry.register("active-conv");
        manager.runShutdownPhase();

        assertThat(shutdownOrder).containsExactly("shutdown");
        assertThat(stopCalled.get()).isTrue();
        assertThat(readiness.isAgentLoaded()).isFalse();
        assertThat(readiness.isProcessUp()).isFalse();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void interruptCancelsActiveStreamAndNotifiesHandlers() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationHandle handle = registry.register("conv-1");
        AtomicInteger interruptCount = new AtomicInteger();
        AgentInterruptHandler interruptHandler = (conversationId, reason) -> {
            interruptCount.incrementAndGet();
            assertThat(conversationId).isEqualTo("conv-1");
            assertThat(reason).isEqualTo(InterruptReason.LIFECYCLE_INTERRUPT);
        };

        ServeOrchestrator orchestrator = new ServeOrchestrator() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                    com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(com.openjiuwen.service.spec.dto.ServeRequest request,
                    com.openjiuwen.service.spec.spi.QueryStreamObserver observer) {
            }

            @Override
            public void cancelActive(String conversationId) {
                registry.cancel(conversationId);
            }

            @Override
            public void resetConversation(String conversationId) {
            }
        };
        DefaultAgentLifecycleManager manager = newManager(readiness, stubAgentHandler(), List.of(), List.of(),
                List.of(interruptHandler), new LifecycleProperties(), registry, orchestrator);

        manager.interrupt("conv-1");

        assertThat(handle.isCancelled()).isTrue();
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    private static DefaultAgentLifecycleManager newManager(DefaultAgentReadiness readiness, AgentHandler agentHandler,
            List<AgentInitHook> initHooks, List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers) {
        return newManager(readiness, agentHandler, initHooks, shutdownHooks, interruptHandlers,
                new LifecycleProperties(), new ActiveStreamRegistry(), null);
    }

    private static DefaultAgentLifecycleManager newManager(DefaultAgentReadiness readiness, AgentHandler agentHandler,
            List<AgentInitHook> initHooks, List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers, LifecycleProperties properties) {
        return newManager(readiness, agentHandler, initHooks, shutdownHooks, interruptHandlers, properties,
                new ActiveStreamRegistry(), null);
    }

    private static DefaultAgentLifecycleManager newManager(DefaultAgentReadiness readiness, AgentHandler agentHandler,
            List<AgentInitHook> initHooks, List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers, LifecycleProperties properties,
            ActiveStreamRegistry registry, ServeOrchestrator orchestrator) {
        AgentServiceIdentity identity = new DefaultAgentServiceIdentity("test-agent");
        AgentLifecycleHooks hooks = new AgentLifecycleHooks(initHooks, shutdownHooks, interruptHandlers);
        InitPhaseExecutor initExecutor = new InitPhaseExecutor(identity, hooks, readiness, providerOf(agentHandler),
                properties);
        ShutdownPhaseExecutor shutdownExecutor = new ShutdownPhaseExecutor(identity, hooks, readiness, registry,
                providerOf(agentHandler), properties);
        ActiveStreamInterruptor interruptor = new ActiveStreamInterruptor(providerOf(orchestrator),
                hooks.interruptHandlers());
        return new DefaultAgentLifecycleManager(initExecutor, shutdownExecutor, interruptor);
    }

    private static AgentHandler stubAgentHandler() {
        return new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                    com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(com.openjiuwen.service.spec.dto.ServeRequest request,
                    com.openjiuwen.service.spec.spi.QueryStreamObserver observer) {
            }
        };
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static final class OrderedInitHook implements AgentInitHook, org.springframework.core.Ordered {
        private final List<String> order;
        private final int orderValue;
        private final String label;

        private OrderedInitHook(List<String> order, int orderValue, String label) {
            this.order = order;
            this.orderValue = orderValue;
            this.label = label;
        }

        @Override
        public void onInit(AgentLifecycleContext context) {
            order.add(label);
        }

        @Override
        public int getOrder() {
            return orderValue;
        }
    }
}
