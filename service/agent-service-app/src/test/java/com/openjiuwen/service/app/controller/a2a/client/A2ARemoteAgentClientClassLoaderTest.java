/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.a2aproject.sdk.client.transport.spi.ClientTransportProvider;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Regression tests for creating an A2A SDK client from worker threads whose
 * context class loader cannot see Java service descriptors.
 */
class A2ARemoteAgentClientClassLoaderTest {
    @Test
    void createClientUsesApplicationClassLoaderForTransportDiscovery() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new NoServicesClassLoader(original));
        try {
            A2ARemoteAgentClient client = new A2ARemoteAgentClient(new A2ARemoteAgentCardRegistry());
            Method createClient = A2ARemoteAgentClient.class.getDeclaredMethod("createClient", AgentCard.class,
                    boolean.class);
            createClient.setAccessible(true);

            assertThatCode(() -> createClient.invoke(client, testCard(), true)).doesNotThrowAnyException();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static AgentCard testCard() {
        return AgentCard.builder().name("remote").description("remote").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Collections.emptyMap())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://localhost:8080/a2a", null, "1.0")))
                .url("http://localhost:8080/a2a").preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    private static final class NoServicesClassLoader extends ClassLoader {
        private static final String TRANSPORT_PROVIDER_SERVICE = "META-INF/services/"
                + ClientTransportProvider.class.getName();

        private NoServicesClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws java.io.IOException {
            if (TRANSPORT_PROVIDER_SERVICE.equals(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }
}
