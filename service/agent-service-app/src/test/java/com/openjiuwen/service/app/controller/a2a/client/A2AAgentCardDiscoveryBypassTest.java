/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteCatalogSource;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.HostedCardProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;
import com.openjiuwen.service.app.hosting.HostedRemoteAgentCatalogs;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests of the unified catalog bypass in {@link A2AAgentCardDiscovery} (FEAT-062
 * L2 {@literal §}4): a present {@link RemoteCatalogSource} bean hands the global
 * static discovery over to the TTL refresher while hosted instance-local
 * discovery keeps running, and an absent source preserves the baseline path.
 *
 * @since 0.1.4
 */
class A2AAgentCardDiscoveryBypassTest {
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void presentCatalogSourceBypassesTheGlobalStaticDiscovery() throws IOException {
        startServer("remote");
        A2AProperties properties = new A2AProperties();
        properties.setRemoteAgents(List.of(remote("remote", baseUrl() + "/global")));
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        A2AAgentCardDiscovery discovery = new A2AAgentCardDiscovery(properties, registry, null, presentProvider());
        try {
            discovery.discoverAll();

            assertThat(requestPaths).isEmpty();
            assertThat(registry.get("remote")).isEmpty();
            assertThat(registry.snapshot().version()).isZero();
        } finally {
            discovery.shutdown();
        }
    }

    @Test
    void absentCatalogSourceKeepsTheGlobalStaticDiscovery() throws IOException {
        startServer("remote");
        A2AProperties properties = new A2AProperties();
        properties.setRemoteAgents(List.of(remote("remote", baseUrl() + "/global")));
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        A2AAgentCardDiscovery discovery = new A2AAgentCardDiscovery(properties, registry, null, absentProvider());
        try {
            discovery.discoverAll();

            assertThat(requestPaths).containsExactly("/global/.well-known/agent-card.json");
            assertThat(registry.get("remote")).isPresent();
            assertThat(registry.get("remote").orElseThrow().timeoutSeconds()).isEqualTo(77);
            assertThat(registry.get("remote").orElseThrow().isStreaming()).isTrue();
        } finally {
            discovery.shutdown();
        }
    }

    @Test
    void presentCatalogSourceStillRunsTheHostedLocalDiscovery() throws IOException {
        startServer("local-tool");
        A2AProperties properties = new A2AProperties();
        HostedCardProperties hostedCardProperties = new HostedCardProperties();
        hostedCardProperties.setRemoteAgents(List.of(remote("local-tool", baseUrl() + "/global")));
        properties.getAgents().put("a", hostedCardProperties);
        A2ARemoteAgentCardRegistry global = new A2ARemoteAgentCardRegistry();
        HostedRemoteAgentCatalogs hostedCatalogs = new HostedRemoteAgentCatalogs(
                HostedAgentDefinitions.builder().add("a", mock(AgentHandler.class)).build(), properties, global,
                event -> {
                });
        A2AAgentCardDiscovery discovery = new A2AAgentCardDiscovery(properties, global, hostedCatalogs,
                presentProvider());
        try {
            discovery.discoverAll();

            assertThat(requestPaths).containsExactly("/global/.well-known/agent-card.json");
            assertThat(global.getAll()).isEmpty();
            assertThat(hostedCatalogs.catalog("a").get("local-tool")).isPresent();
            assertThat(hostedCatalogs.catalog("a").get("local-tool").orElseThrow().timeoutSeconds()).isEqualTo(77);
        } finally {
            discovery.shutdown();
        }
    }

    private void startServer(String cardName) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> replyCard(exchange, cardName));
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void replyCard(HttpExchange exchange, String cardName) throws IOException {
        try (exchange) {
            requestPaths.add(exchange.getRequestURI().getPath());
            String endpoint = baseUrl() + exchange.getRequestURI().getPath()
                    .replace("/.well-known/agent-card.json", "");
            byte[] body = new ObjectMapper().writeValueAsBytes(card(cardName, endpoint));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static ObjectProvider<RemoteCatalogSource> presentProvider() {
        return staticProvider(mock(RemoteCatalogSource.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RemoteCatalogSource> staticProvider(RemoteCatalogSource source) {
        ObjectProvider<RemoteCatalogSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RemoteCatalogSource> absentProvider() {
        ObjectProvider<RemoteCatalogSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static RemoteAgentProperties remote(String name, String url) {
        RemoteAgentProperties remote = new RemoteAgentProperties();
        remote.setName(name);
        remote.setUrl(url);
        remote.setTimeoutSeconds(77);
        remote.setStreaming(true);
        return remote;
    }

    private static AgentCard card(String name, String endpoint) {
        AgentInterface iface = new AgentInterface("JSONRPC", endpoint, null, "1.0");
        return AgentCard.builder().name(name).description(name).version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(iface)).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
