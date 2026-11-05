/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.controller.a2a.WriteThrottlingTaskStore;
import com.openjiuwen.service.app.storage.A2ATaskStoreProvider;
import com.openjiuwen.service.app.storage.A2ATaskStoreProvider.TaskStoreContext;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TaskStoreProviderTest {
    @Test
    void ordinaryScopeUsesRegisteredAgentAndClosesResourcesOnce() {
        var scope = new AtomicReference<TaskStoreContext>();
        var closed = new AtomicInteger();
        var service = new ServiceProperties();
        service.setAgentId("registered-agent");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
                .withBean(ServeOrchestrator.class, () -> mock(ServeOrchestrator.class))
                .withBean(RequestHandler.class, () -> mock(RequestHandler.class))
                .withBean(MiddlewareProperties.class, () -> properties("custom"))
                .withBean(ServiceProperties.class, () -> service)
                .withPropertyValues("spring.application.name=business")
                .withBean(A2ATaskStoreProvider.class, () -> provider(ctx -> {
                    scope.set(ctx);
                    ctx.onClose().accept(closed::incrementAndGet);
                    return new InMemoryTaskStore();
                })).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskStore.class)).isInstanceOf(WriteThrottlingTaskStore.class)
                            .isInstanceOf(TaskStateProvider.class);
                    assertThat(scope.get().applicationName()).isEqualTo("business");
                    assertThat(scope.get().agentId()).isEqualTo("registered-agent");
                    assertThat(scope.get().connectionRef()).isEqualTo("primary");
                    assertThat(closed).hasValue(0);
                });
        assertThat(closed).hasValue(1);
    }

    @Test
    void extensionRejectsMissingDuplicateNullAndAlreadyWrappedStores() {
        var ctx = new TaskStoreContext("app", "agent", "primary", cleanup -> { });
        var props = properties("custom");
        var a2a = new A2AProperties();
        var valid = provider(scope -> new InMemoryTaskStore());
        assertThatThrownBy(() -> A2AAutoConfiguration.createTaskStore(props, null, a2a, ctx, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> A2AAutoConfiguration.createTaskStore(props, null, a2a, ctx, List.of(valid, valid)))
                .isInstanceOf(IllegalStateException.class);
        for (var invalid : List.of(provider(scope -> null),
                provider(scope -> new WriteThrottlingTaskStore(new InMemoryTaskStore())))) {
            assertThatThrownBy(() -> A2AAutoConfiguration.createTaskStore(props, null, a2a, ctx, List.of(invalid)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> A2AAutoConfiguration.createTaskStore(props, null, a2a))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void builtinsIgnoreExtensionProvidersAndConnectionReference() {
        var props = properties("in_memory");
        props.getCheckpointer().setConnectionRef(null);
        var ctx = new TaskStoreContext("app", "agent", "default", cleanup -> { });
        TaskStore result = A2AAutoConfiguration.createTaskStore(props, null, new A2AProperties(), ctx,
                List.of(provider(scope -> { throw new AssertionError("Must not select extension"); })));
        assertThat(result).isInstanceOf(InMemoryTaskStore.class);
    }

    private static MiddlewareProperties properties(String type) {
        var props = new MiddlewareProperties();
        props.getCheckpointer().setType(type);
        props.getCheckpointer().setConnectionRef("primary");
        return props;
    }

    private static A2ATaskStoreProvider provider(Function<TaskStoreContext, TaskStore> factory) {
        return new A2ATaskStoreProvider() {
            public String type() { return "custom"; }
            public TaskStore create(TaskStoreContext context) { return factory.apply(context); }
        };
    }
}
