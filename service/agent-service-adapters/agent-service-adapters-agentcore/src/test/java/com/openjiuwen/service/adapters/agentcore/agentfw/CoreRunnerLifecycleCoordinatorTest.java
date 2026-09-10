/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;

import org.junit.jupiter.api.Test;

/**
 * Verifies shared Runner ownership and registration lifecycle validation.
 *
 * @since 0.1.2
 */
class CoreRunnerLifecycleCoordinatorTest {
    @Test
    void sharedRunnerStartsAndStopsOnceWithoutHandlerOwnership() {
        var middleware = mock(MiddlewareAdapterRegistrar.class);
        var external = mock(ExternalSvcAdapterRegistrar.class);
        var first = new JiuwenCoreAgentHandler(new Object());
        var second = new JiuwenCoreAgentHandler(new Object(), middleware, external);
        var definitions = HostedAgentDefinitions.builder().add("first", first).add("second", second).build();
        var coordinator = new CoreRunnerLifecycleCoordinator(middleware, external);
        try (var runner = mockStatic(Runner.class)) {
            coordinator.prepare("app", definitions);
            coordinator.start();
            first.start();
            second.start();
            first.stop();
            second.stop();
            coordinator.start();
            runner.verify(Runner::start, times(1));
            runner.verify(Runner::stop, times(0));
            coordinator.stop();
            coordinator.stop();
            runner.verify(Runner::stop, times(1));
            verify(middleware).applyToRunnerConfig(RunnerConfig.getRunnerConfig());
            verify(external).registerToRunner();
        }
    }

    @Test
    void rejectsDuplicateAgentAndRegistrarBeforeRunnerChanges() {
        Object agent = new Object();
        var first = new JiuwenCoreAgentHandler(agent);
        var second = new JiuwenCoreAgentHandler(agent);
        var definitions = HostedAgentDefinitions.builder().add("first", first).add("second", second).build();
        var coordinator = new CoreRunnerLifecycleCoordinator(null, null);
        assertThatThrownBy(() -> coordinator.prepare("app", definitions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("more than once");
        var custom = new JiuwenCoreAgentHandler(new Object(), mock(MiddlewareAdapterRegistrar.class));
        assertThatThrownBy(() -> coordinator.prepare("app",
                HostedAgentDefinitions.builder().add("custom", custom).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different middleware registrar");
        var customExternal = new JiuwenCoreAgentHandler(new Object(), ExternalSvcAdapterRegistrar.noop());
        assertThatThrownBy(() -> coordinator.prepare("app",
                HostedAgentDefinitions.builder().add("custom-external", customExternal).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different external registrar");
    }

    @Test
    void rejectsAlreadyStartedAndAlreadyBoundHandlers() {
        var handler = new JiuwenCoreAgentHandler(new Object());
        var definitions = HostedAgentDefinitions.builder().add("a", handler).build();
        var coordinator = new CoreRunnerLifecycleCoordinator(null, null);
        coordinator.prepare("app", definitions);
        assertThatThrownBy(() -> new CoreRunnerLifecycleCoordinator(null, null).prepare("app", definitions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already started or bound");
        try (var runner = mockStatic(Runner.class)) {
            var started = new JiuwenCoreAgentHandler(new Object());
            started.start();
            try {
                assertThatThrownBy(() -> new CoreRunnerLifecycleCoordinator(null, null).prepare("app",
                        HostedAgentDefinitions.builder().add("started", started).build()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already started or bound");
            } finally {
                started.stop();
            }
        }
    }
}
