/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration.HostedResources;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.controller.a2a.A2AProtocolAdapter;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackHandler;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackStore;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCapabilityGate;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.hosting.HostedAgentCardFactory;
import com.openjiuwen.service.app.hosting.HostedAgentRuntime;
import com.openjiuwen.service.app.hosting.HostedIngressResolver;
import com.openjiuwen.service.app.hosting.HostedLifecycleCoordinator;
import com.openjiuwen.service.app.hosting.HostedRuntimeAssembler;
import com.openjiuwen.service.app.hosting.HostedRuntimeCatalog;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationDispatcher;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.hosting.HostedSharedLifecycle;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Opt-in hosted assembly. Compatibility facades are lazy and only select the
 * default target; the assembler never consumes these facades.
 *
 * @since 0.1.2
 */
@AutoConfiguration(before = {AgentServiceAutoConfiguration.class, A2AAutoConfiguration.class})
@ConditionalOnBean(HostedAgentDefinitions.class)
@EnableConfigurationProperties({A2AProperties.class, ServiceProperties.class, LifecycleProperties.class})
public class HostedRuntimeAutoConfiguration {
    @Autowired
    private ConfigurableListableBeanFactory beanFactory;

    @Autowired
    private ObjectProvider<TaskAdmissionGate> admissionGate;

    @Autowired
    private ObjectProvider<RuntimeRedisClient> redisClient;

    @Autowired
    private ObjectProvider<MiddlewareProperties> middleware;

    @Autowired
    private ObjectProvider<HostedRuntimeAssembler.Extension> extensions;

    @Autowired
    private ObjectProvider<HostedSharedLifecycle> sharedLifecycles;

    @Autowired
    private Environment environment;

    @Bean
    public HostedRuntimeCatalog hostedRuntimeCatalog(HostedAgentDefinitions definitions) {
        if (beanFactory.getBeanNamesForType(HostedAgentDefinitions.class).length != 1) {
            throw new IllegalStateException("Hosted mode requires exactly one HostedAgentDefinitions bean");
        }
        return new HostedRuntimeCatalog(definitions);
    }

    @Bean
    public HostedIngressResolver hostedIngressResolver(HostedRuntimeCatalog catalog) {
        return new HostedIngressResolver(catalog);
    }

    @Bean
    public HostedAgentCardFactory hostedAgentCardFactory(HostedAgentDefinitions definitions, A2AProperties properties,
            ServiceProperties serviceProperties) {
        if (serviceProperties.getAgentId() != null && !serviceProperties.getAgentId().isBlank()) {
            throw new IllegalStateException("openjiuwen.service.agent-id conflicts with HostedAgentDefinitions");
        }
        Binder.get(environment).bind("openjiuwen.service.a2a.agents",
                Bindable.mapOf(String.class, A2AProperties.HostedCardProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
        return new HostedAgentCardFactory(definitions, properties, serviceProperties);
    }

    @Bean(destroyMethod = "close")
    public HostedResources hostedResources(A2AProperties properties, HostedAgentDefinitions definitions) {
        return new HostedResources(properties, definitions.entries().size(), admissionGate.getIfAvailable());
    }

    @Bean
    public RemoteInvocationDispatcher hostedRemoteDispatcher(A2AProperties properties) {
        var limits = properties.getRemoteInvocation();
        return new RemoteInvocationDispatcher(limits.getMaxConcurrency(), limits.getMaxQueueSize(),
                Duration.ofSeconds(limits.getQueueTimeoutSeconds()));
    }

    @Bean
    public HostedRuntimeAssembler hostedRuntimeAssembler(A2AProperties properties, HostedResources resources,
            RemoteInvocationDispatcher dispatcher, HostedAgentCardFactory cards, RemoteAgentCaller remoteCaller) {
        var dependencies = new HostedRuntimeAssembler.Dependencies(properties, middleware.getIfAvailable(),
                redisClient.getIfAvailable(), remoteCaller, beanFactory.getBean(A2AProtocolAdapter.class),
                admissionGate.getIfAvailable(), resources, dispatcher, cards, beanFactory,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
        return new HostedRuntimeAssembler(dependencies, extensions.orderedStream().toList());
    }

    @Bean
    public HostedLifecycleCoordinator hostedLifecycleCoordinator(HostedAgentDefinitions definitions,
            HostedRuntimeAssembler assembler, HostedRuntimeCatalog catalog, AgentServiceIdentity identity,
            DefaultAgentReadiness readiness) {
        var configuration = new HostedLifecycleCoordinator.Configuration(definitions, identity,
                beanFactory.getBean(AgentLifecycleHooks.class), readiness, beanFactory.getBean(LifecycleProperties.class),
                sharedLifecycles.orderedStream().toList(), beanFactory.getBean(HostedResources.class),
                beanFactory.getBean(ActiveStreamInterruptor.class));
        return new HostedLifecycleCoordinator(configuration, assembler, catalog);
    }

    @Bean(name = "hostedDefaultAgentHandler", destroyMethod = "")
    @Primary
    public AgentHandler hostedDefaultAgentHandler(HostedRuntimeCatalog catalog) {
        return facade(AgentHandler.class, catalog, HostedAgentRuntime::handler);
    }

    @Bean(name = "hostedDefaultOrchestrator", destroyMethod = "")
    @Primary
    public ServeOrchestrator hostedDefaultOrchestrator(HostedRuntimeCatalog catalog) {
        return facade(ServeOrchestrator.class, catalog, HostedAgentRuntime::orchestrator);
    }

    @Bean(name = "hostedDefaultTaskStore", destroyMethod = "")
    @Primary
    public TaskStore hostedDefaultTaskStore(HostedRuntimeCatalog catalog) {
        return facade(TaskStore.class, catalog, HostedAgentRuntime::taskStore);
    }

    @Bean(name = "hostedDefaultRequestHandler", destroyMethod = "")
    @Primary
    public RequestHandler hostedDefaultRequestHandler(HostedRuntimeCatalog catalog) {
        return facade(RequestHandler.class, catalog, HostedAgentRuntime::requestHandler);
    }

    @Bean(name = "hostedDefaultCallbackStore", destroyMethod = "")
    @Primary
    public A2aPushNotificationCallbackStore hostedDefaultCallbackStore(HostedRuntimeCatalog catalog) {
        return facade(A2aPushNotificationCallbackStore.class, catalog, target -> target.execution().callbackStore());
    }

    @Bean(name = "hostedDefaultCallbackHandler", destroyMethod = "")
    @Primary
    public A2aPushNotificationCallbackHandler hostedDefaultCallbackHandler(HostedRuntimeCatalog catalog) {
        return facade(A2aPushNotificationCallbackHandler.class, catalog, HostedAgentRuntime::orchestrator);
    }

    @Bean
    public A2aPushNotificationCapabilityGate hostedPushCapabilityGate(A2AProperties properties) {
        return new A2aPushNotificationCapabilityGate(properties, null, null, null) {
            @Override
            public boolean isPushNotificationsEnabled() {
                return properties.isPushNotifications();
            }
        };
    }

    @Bean
    public SmartInitializingSingleton hostedDeclarationValidation(HostedAgentDefinitions definitions) {
        return () -> {
            List<AgentHandler> registered = definitions.entries().stream().map(HostedAgentDefinitions.Entry::handler)
                    .toList();
            for (String name : beanFactory.getBeanNamesForType(AgentHandler.class)) {
                if (name.equals("hostedDefaultAgentHandler")) {
                    continue;
                }
                AgentHandler handler = beanFactory.getBean(name, AgentHandler.class);
                if (registered.stream().noneMatch(candidate -> candidate == handler)) {
                    throw new IllegalStateException("Unregistered AgentHandler conflicts with hosted mode: " + name);
                }
                if (beanFactory.containsBeanDefinition(name) && beanFactory.getBeanDefinition(name).isPrimary()) {
                    throw new IllegalStateException("Hosted Handler beans must use qualifiers, not @Primary: " + name);
                }
            }
            rejectUnscopedReplacement(TaskStore.class, "hostedDefaultTaskStore");
            rejectUnscopedReplacement(RequestHandler.class, "hostedDefaultRequestHandler");
            rejectUnscopedReplacement(ServeOrchestrator.class, "hostedDefaultOrchestrator");
        };
    }

    private void rejectUnscopedReplacement(Class<?> type, String facadeName) {
        for (String name : beanFactory.getBeanNamesForType(type)) {
            if (!name.equals(facadeName)) {
                throw new IllegalStateException("Unscoped " + type.getSimpleName()
                        + " replacement conflicts with hosted assembly: " + name);
            }
        }
    }

    private static <T> T facade(Class<T> type, HostedRuntimeCatalog catalog, Function<HostedAgentRuntime, T> select) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Hosted default facade: " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new IllegalStateException("Unexpected Object method");
                };
            }
            try {
                return method.invoke(select.apply(catalog.defaultRuntime()), args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        return type.cast(proxy);
    }
}
