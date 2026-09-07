/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.adapters.common.autoconfigure.ExternalSecurityAutoConfiguration;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.ExternalAuthenticator;
import com.openjiuwen.service.spec.security.ExternalTargetRef;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Exercises Spring SPI wiring and the actual SDK HTTP transport, including tenant URL semantics.
 */
class A2ARemoteAgentClientSecurityTest {
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class,
                    ExternalSecurityAutoConfiguration.class))
            .withBean(ServeOrchestrator.class, () -> mock(ServeOrchestrator.class))
            .withBean(RequestHandler.class, () -> mock(RequestHandler.class));

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void springAuthenticatorAddsTargetHeadersToJsonRpcAndSseAndCachesMaterials() throws Exception {
        String endpoint = startServer();
        List<ExternalTargetRef> targets = new CopyOnWriteArrayList<>();
        ExternalAuthenticator authenticator = (target, config) -> {
            targets.add(target);
            assertThat(config.isNoneType()).isTrue();
            return new AuthMaterial(Map.of("Authorization", "Bearer test-" + target.targetId()), Map.of(), Map.of());
        };
        contextRunner.withBean(ExternalAuthenticator.class, () -> authenticator).run(context -> {
            A2ARemoteAgentCardRegistry registry = context.getBean(A2ARemoteAgentCardRegistry.class);
            registry.register("first", card(endpoint, null), 5, true);
            registry.register("second", card(endpoint, null), 5, true);
            A2ARemoteAgentClient client = context.getBean(A2ARemoteAgentClient.class);

            invoke(client, "first", false);
            invoke(client, "first", false);
            invoke(client, "first", true);
            invoke(client, "second", true);

            assertThat(targets).extracting(ExternalTargetRef::targetId).containsExactly("first", "first", "second");
            assertThat(targets).allSatisfy(target -> {
                assertThat(target.adapterType()).isEqualTo("A2A");
                assertThat(target.url()).isEqualTo(endpoint);
            });
            assertThat(requests).extracting(ReceivedRequest::authorization)
                    .containsExactly("Bearer test-first", "Bearer test-first",
                            "Bearer test-first", "Bearer test-second");
            assertThat(requests).extracting(request -> request.body().get("method").getAsString())
                    .containsExactly("SendMessage", "SendMessage", "SendStreamingMessage", "SendStreamingMessage");
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.path()).isEqualTo("/a2a");
                assertThat(request.body().getAsJsonObject("params").get("tenant").getAsString()).isEmpty();
                assertThat(request.body().toString()).doesNotContain("Bearer", "Authorization", "test-first");
            });
        });
    }

    @Test
    void noAuthenticatorPreservesAnonymousSdkProviderPath() throws Exception {
        String endpoint = startServer();
        contextRunner.run(context -> {
            A2ARemoteAgentCardRegistry registry = context.getBean(A2ARemoteAgentCardRegistry.class);
            registry.register("remote", card(endpoint, null), 5, true);
            A2ARemoteAgentClient client = context.getBean(A2ARemoteAgentClient.class);
            HeaderInjectingA2AHttpClient http = (HeaderInjectingA2AHttpClient) client
                    .createHttpClient(registry.get("remote").orElseThrow());
            assertThat(http.unwrap()).isInstanceOf(TestTaggingA2AHttpClientProvider.TaggingA2AHttpClient.class);
            invoke(client, "remote", false);
            invoke(client, "remote", true);
            assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request.authorization()).isNull());
        });
    }

    @Test
    void existingCardTenantPathRemainsSdkOwned() throws Exception {
        String endpoint = startServer();
        contextRunner.run(context -> {
            A2ARemoteAgentCardRegistry registry = context.getBean(A2ARemoteAgentCardRegistry.class);
            registry.register("remote", card(endpoint, "card-default"), 5, true);
            A2ARemoteAgentClient client = context.getBean(A2ARemoteAgentClient.class);
            invoke(client, "remote", false);
            invoke(client, "remote", true);
            assertThat(requests).extracting(ReceivedRequest::path)
                    .containsExactly("/a2a/card-default", "/a2a/card-default");
            assertThat(requests).allSatisfy(request -> assertThat(request.body().toString())
                    .doesNotContain("runtime.a2a.protocolTenant"));
        });
    }

    @Test
    void authenticatorFailureDoesNotFallBackToAnonymousHttp() throws Exception {
        String endpoint = startServer();
        ExternalAuthenticator authenticator = (target, config) -> {
            throw new IllegalStateException("Test authentication unavailable");
        };
        contextRunner.withBean(ExternalAuthenticator.class, () -> authenticator).run(context -> {
            context.getBean(A2ARemoteAgentCardRegistry.class).register("remote", card(endpoint, null), 5, false);
            assertThatThrownBy(() -> invoke(context.getBean(A2ARemoteAgentClient.class), "remote", false))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Test authentication unavailable");
            assertThat(requests).isEmpty();
        });
    }

    @Test
    void queryAuthenticationIsRejectedBeforeHttp() throws Exception {
        String endpoint = startServer();
        ExternalAuthenticator authenticator = (target, config) ->
                new AuthMaterial(Map.of(), Map.of("api_key", "test-only"), Map.of());
        contextRunner.withBean(ExternalAuthenticator.class, () -> authenticator).run(context -> {
            context.getBean(A2ARemoteAgentCardRegistry.class).register("remote", card(endpoint, null), 5, false);
            assertThatThrownBy(() -> invoke(context.getBean(A2ARemoteAgentClient.class), "remote", false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("query parameters are not supported");
            assertThat(requests).isEmpty();
        });
    }

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", this::respond);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(new ReceivedRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            writeResponse(exchange, body);
        }
    }

    static void writeResponse(HttpExchange exchange, JsonObject request) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", JsonParser.parseString("""
                {"message":{"messageId":"reply","role":"ROLE_AGENT","parts":[{"text":"ok"}]}}
                """));
        boolean streaming = "SendStreamingMessage".equals(request.get("method").getAsString());
        String wire = streaming ? "data: " + response + "\n\n" : response.toString();
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void invoke(A2ARemoteAgentClient client, String name, boolean streaming) throws Exception {
        RemoteCall call = new RemoteCall(name, "hello", "ctx", null, Map.of(), Map.of(), streaming);
        RemoteCallOutcome outcome = client.callOutcome(call, mock(RemoteAgentCaller.EventObserver.class))
                .get(10, TimeUnit.SECONDS);
        assertThat(outcome.result()).isEqualTo("ok");
    }

    static AgentCard card(String endpoint, String tenant) {
        return AgentCard.builder().name("remote").description("remote").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Map.of())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint, tenant, "1.0"))).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    private record ReceivedRequest(String path, String authorization, JsonObject body) {
    }
}
