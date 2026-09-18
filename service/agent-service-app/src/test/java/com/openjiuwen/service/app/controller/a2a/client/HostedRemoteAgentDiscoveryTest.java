/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.hosting.HostedRemoteAgentCatalogs;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises bound catalogs through Card discovery and actual HTTP calls. */
class HostedRemoteAgentDiscoveryTest {
    @Test
    void localOverridesAndAdditionsKeepGlobalEndpointsIsolated() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        configureServer(server, base, requests);
        server.start();
        var values = new MapConfigurationPropertySource(Map.of(
                "openjiuwen.service.a2a.remote-agents[0].name", "remote",
                "openjiuwen.service.a2a.remote-agents[0].url", base + "/global",
                "openjiuwen.service.a2a.remote-agents[0].streaming", "true",
                "openjiuwen.service.a2a.agents.a.remote-agents[0].name", "remote",
                "openjiuwen.service.a2a.agents.a.remote-agents[0].url", base + "/a2a/agents/local",
                "openjiuwen.service.a2a.agents.a.remote-agents[1].name", "extra",
                "openjiuwen.service.a2a.agents.a.remote-agents[1].url", base + "/extra"));
        A2AProperties properties = new Binder(values).bind("openjiuwen.service.a2a", A2AProperties.class).get();
        var global = new A2ARemoteAgentCardRegistry();
        var catalogs = new HostedRemoteAgentCatalogs(definitions(), properties, global, event -> { });
        var discovery = new A2AAgentCardDiscovery(properties, global, catalogs);
        var client = new A2ARemoteAgentClient(global);
        try {
            discovery.discoverAll();
            assertThat(catalogs.catalog("b")).isSameAs(global);
            assertThat(catalogs.catalog("a").get("remote").orElseThrow().isStreaming()).isFalse();
            assertThat(catalogs.catalog("b").get("extra")).isEmpty();
            A2ARemoteAgentClientSecurityTest.invoke(client, "remote", true);
            RemoteAgentCaller local = client.bindCatalog(catalogs.catalog("a"));
            A2ARemoteAgentClientSecurityTest.invoke(local, "remote", true);
            A2ARemoteAgentClientSecurityTest.invoke(local, "extra", false);
            assertThat(requests).containsExactly("/global:SendStreamingMessage",
                    "/a2a/agents/local:SendMessage", "/extra:SendMessage");
        } finally {
            client.shutdown();
            discovery.shutdown();
            server.stop(0);
        }
    }

    @Test
    void failedLocalDiscoveryKeepsRetriesIsolatedWithoutFallback() {
        var properties = new A2AProperties();
        for (String id : List.of("a", "b")) {
            var local = new A2AProperties.HostedCardProperties();
            local.setRemoteAgents(List.of(remote("remote", "http://" + id)));
            properties.getAgents().put(id, local);
        }
        var global = new A2ARemoteAgentCardRegistry();
        global.register("remote", A2ARemoteAgentClientSecurityTest.card("http://global", null));
        var catalogs = new HostedRemoteAgentCatalogs(definitions(), properties, global, event -> { });
        var isAvailable = new AtomicBoolean();
        var discovery = new A2AAgentCardDiscovery(properties, global, catalogs) {
            @Override
            org.a2aproject.sdk.spec.AgentCard fetchCardInternal(String url) {
                if (!isAvailable.get()) {
                    throw new org.springframework.web.client.ResourceAccessException("not ready");
                }
                return A2ARemoteAgentClientSecurityTest.card(url, null);
            }
        };
        var scheduler = mock(ScheduledExecutorService.class);
        var retries = new ArrayList<Runnable>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    retries.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        assertThat(ReflectionTestUtils.getField(discovery, "retryExecutor"))
                .isInstanceOfSatisfying(ScheduledExecutorService.class, ScheduledExecutorService::shutdown);
        ReflectionTestUtils.setField(discovery, "retryExecutor", scheduler);
        try {
            discovery.discoverAll();
            assertThat(catalogs.catalog("a").get("remote")).isEmpty();
            assertThat(catalogs.catalog("b").get("remote")).isEmpty();
            assertThat(ReflectionTestUtils.getField(discovery, "retryFutures"))
                    .isInstanceOfSatisfying(Map.class, futures -> assertThat(futures).hasSize(2));
            isAvailable.set(true);
            retries.forEach(Runnable::run);
            assertThat(catalogs.catalog("a").resolveUrl("remote")).isEqualTo("http://a");
            assertThat(catalogs.catalog("b").resolveUrl("remote")).isEqualTo("http://b");
            assertThat(ReflectionTestUtils.getField(discovery, "retryFutures"))
                    .isInstanceOfSatisfying(Map.class, futures -> assertThat(futures).isEmpty());
        } finally {
            discovery.shutdown();
        }
    }

    @Test
    void globalUpdatesReachOnlyInheritedNamesAndEventsCarryTheirOwner() {
        var events = new ArrayList<RemoteAgentCatalogChangedEvent>();
        var properties = new A2AProperties();
        var local = new A2AProperties.HostedCardProperties();
        local.setRemoteAgents(List.of(remote("overridden", "http://local")));
        properties.getAgents().put("a", local);
        var global = new A2ARemoteAgentCardRegistry();
        var catalogs = new HostedRemoteAgentCatalogs(definitions(), properties, global,
                event -> assertThat(event).isInstanceOfSatisfying(RemoteAgentCatalogChangedEvent.class, events::add));
        global.register("inherited", A2ARemoteAgentClientSecurityTest.card("http://global", null));
        global.register("overridden", A2ARemoteAgentClientSecurityTest.card("http://wrong", null));
        catalogs.onGlobalCatalogChanged(new RemoteAgentCatalogChangedEvent(global.snapshot()));
        assertThat(catalogs.catalog("a").resolveUrl("inherited")).isEqualTo("http://global");
        assertThat(catalogs.catalog("a").get("overridden")).isEmpty();
        assertThat(events).singleElement().satisfies(event -> assertThat(event.agentId()).isEqualTo("a"));
        assertThatThrownBy(() -> catalogs.catalog("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void configureServer(HttpServer server, String base, List<String> requests) {
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/.well-known/agent-card.json")) {
                    String endpoint = base + path.replace("/.well-known/agent-card.json", "");
                    byte[] body = new ObjectMapper().writeValueAsBytes(
                            A2ARemoteAgentClientSecurityTest.card(endpoint, null));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    requests.add(path + ":" + body.get("method").getAsString());
                    assertThat(body.getAsJsonObject("params").getAsJsonObject("message")
                            .get("contextId").getAsString()).isEqualTo("ctx");
                    A2ARemoteAgentClientSecurityTest.writeResponse(exchange, body);
                }
            }
        });
    }

    private static HostedAgentDefinitions definitions() {
        return HostedAgentDefinitions.builder().add("a", mock(AgentHandler.class)).add("b", mock(AgentHandler.class))
                .defaultAgent("b").build();
    }

    private static A2AProperties.RemoteAgentProperties remote(String name, String url) {
        var remote = new A2AProperties.RemoteAgentProperties();
        remote.setName(name);
        remote.setUrl(url);
        return remote;
    }
}
