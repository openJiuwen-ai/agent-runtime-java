/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Verifies hosted endpoints, remote discovery and deployment path compatibility.
 *
 * @since 0.1.2
 */
class HostedDeploymentContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @Timeout(60)
    void preservesPrefixedDiscoveryDisabledEntriesAndShutdown() throws Exception {
        try (var context = application(true).run()) {
            String base = "http://127.0.0.1:"
                    + context.getEnvironment().getRequiredProperty("local.server.port") + "/gateway";
            for (String path : List.of("/.well-known/agent-card.json",
                    "/a2a/agents/a/.well-known/agent-card.json", "/a2a/agents/b/.well-known/agent-card.json")) {
                var card = send(base + path, null);
                assertThat(card.statusCode()).isEqualTo(200);
                var json = JSON.readTree(card.body());
                String url = json.path("supportedInterfaces").get(0).path("url").asText();
                String id = path.contains("/agents/a/") ? "a" : "b";
                assertThat(url).startsWith(base + "/a2a");
                var reply = send(url, rpc());
                assertThat(reply.statusCode()).isEqualTo(200);
                assertThat(reply.body()).contains("target-" + id);
            }
            var rest = Map.of("agent_id", "a", "conversation_id", "deployment", "message", "hello");
            assertThat(send(base + "/v1/query", rest).body()).contains("target-a");
            for (String path : List.of("/query", "/v1/query/reactive")) {
                assertThat(send(base + path, rest).statusCode()).isEqualTo(404);
            }
            assertThat(send(base + "/a2a/agents", null).body()).contains("\"defaultAgent\":\"b\"");
            context.getBean(HostedLifecycleCoordinator.class).runShutdownPhase();
            for (String path : List.of("/.well-known/agent-card.json", "/a2a/agents",
                    "/a2a/agents/b/.well-known/agent-card.json")) {
                assertThat(send(base + path, null).statusCode()).isEqualTo(503);
            }
            assertThat(send(base + "/v1/query", rest).statusCode()).isEqualTo(503);
            assertThat(send(base + "/a2a/agents/b", rpc()).statusCode()).isEqualTo(503);
        }
    }

    @Test
    @Timeout(60)
    void discoveryPreservesNamedUrlsPrefixesAndAliases() throws Exception {
        try (var target = application(true).run()) {
            String base = "http://127.0.0.1:"
                    + target.getEnvironment().getRequiredProperty("local.server.port") + "/gateway";
            try (var caller = application(false).properties(
                    "openjiuwen.service.a2a.remote-agents[0].name=alias-first",
                    "openjiuwen.service.a2a.remote-agents[0].url=" + base + "/a2a/agents/a/",
                    "openjiuwen.service.a2a.remote-agents[1].name=alias-second",
                    "openjiuwen.service.a2a.remote-agents[1].url=" + base + "/a2a/agents/b").run()) {
                var registry = caller.getBean(A2ARemoteAgentCardRegistry.class);
                assertThat(registry.getAll()).hasSize(2);
                for (var entry : Map.of("alias-first", "a", "alias-second", "b").entrySet()) {
                    String alias = entry.getKey();
                    String id = entry.getValue();
                    assertThat(registry.resolveUrl(alias)).isEqualTo(base + "/a2a/agents/" + id);
                    assertThat(registry.get(alias).orElseThrow().card().name()).isNotEqualTo(alias);
                    var result = caller.getBean(RemoteAgentCaller.class).callOutcome(
                            new RemoteCall(alias, "hello", "original-context", null, Map.of()), null)
                            .get(10, TimeUnit.SECONDS);
                    assertThat(result.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
                    assertThat(result.result()).contains("target-" + id);
                    var catalog = target.getBean(HostedRuntimeCatalog.class);
                    assertThat(catalog.resolve(id).taskStore().get(result.remoteTaskId()).contextId())
                            .isEqualTo("original-context");
                    assertThat(catalog.resolve("a".equals(id) ? "b" : "a").taskStore()
                            .get(result.remoteTaskId())).isNull();
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void legacyApplicationDoesNotPublishHostedDirectoryOrNamedEntrypoints() throws Exception {
        try (var context = application(false).run()) {
            String base = "http://127.0.0.1:"
                    + context.getEnvironment().getRequiredProperty("local.server.port") + "/gateway";
            assertThat(send(base + "/a2a", rpc()).body()).contains("target-legacy");
            for (String path : List.of("/a2a/agents", "/a2a/agents/a/.well-known/agent-card.json")) {
                assertThat(send(base + path, null).statusCode()).isEqualTo(404);
            }
            assertThat(send(base + "/a2a/agents/a", rpc()).statusCode()).isEqualTo(404);
            assertThat(send(base + "/v1/query", Map.of("agent_id", "unknown", "conversation_id", "legacy",
                    "message", "hello")).body()).contains("target-legacy");
        }
    }

    private static SpringApplicationBuilder application(boolean isHosted) {
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET)
                .registerShutdownHook(false).properties("server.port=0", "server.servlet.context-path=/gateway",
                        "spring.application.name=deployment-test", "test.hosted=" + isHosted,
                        "logging.level.root=WARN", "openjiuwen.service.query.legacy-path-enabled=false",
                        "openjiuwen.service.query.webflux.enabled=false");
    }

    private HttpResponse<String> send(String url, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> rpc() {
        String id = UUID.randomUUID().toString();
        return Map.of("jsonrpc", "2.0", "id", id, "method", "SendMessage", "params", Map.of("message",
                Map.of("role", "ROLE_USER", "messageId", id, "contextId", id,
                        "parts", List.of(Map.of("text", "hello")))));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean
        @ConditionalOnProperty(name = "test.hosted", havingValue = "true")
        HostedAgentDefinitions definitions() {
            return HostedAgentDefinitions.builder().add("a", new Handler("a"))
                    .add("b", new Handler("b")).defaultAgent("b").build();
        }

        @Bean
        @ConditionalOnProperty(name = "test.hosted", havingValue = "false")
        AgentHandler legacyHandler() {
            return new Handler("legacy");
        }
    }

    static final class Handler implements AgentHandler {
        private final String id;

        Handler(String id) {
            this.id = id;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("role", "assistant", "content", "target-" + id),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
            observer.onComplete();
        }
    }
}
