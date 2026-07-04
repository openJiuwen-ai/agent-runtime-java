/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ShutdownPhaseExecutorTest
 *
 * @since 2026-07-03
 */
class ShutdownPhaseExecutorTest {
    @Test
    void drainTimeoutStillRunsShutdownHooksAndStop() {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        readiness.markAgentLoaded(true);
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        registry.register("stuck-stream");

        LifecycleProperties properties = new LifecycleProperties();
        properties.setShutdownTimeoutMs(50L);

        AtomicBoolean shutdownHookRan = new AtomicBoolean(false);
        AtomicBoolean stopCalled = new AtomicBoolean(false);
        AgentHandler handler = trackingHandler(stopCalled);

        ShutdownPhaseExecutor executor = new ShutdownPhaseExecutor(new DefaultAgentServiceIdentity("shutdown-test"),
                new AgentLifecycleHooks(List.of(), List.of(context -> shutdownHookRan.set(true)), List.of()), readiness,
                registry, providerOf(handler), properties);

        executor.run();

        assertThat(shutdownHookRan.get()).isTrue();
        assertThat(stopCalled.get()).isTrue();
        assertThat(readiness.isAgentLoaded()).isFalse();
        assertThat(readiness.isProcessUp()).isFalse();
    }

    private static AgentHandler trackingHandler(AtomicBoolean stopCalled) {
        return new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }

            @Override
            public void stop() {
                stopCalled.set(true);
            }
        };
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
