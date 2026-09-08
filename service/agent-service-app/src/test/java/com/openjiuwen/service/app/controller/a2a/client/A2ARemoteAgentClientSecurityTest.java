/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void springAuthenticatorCachesHeadersForJsonRpcAndSse() throws Exception {
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
            HeaderInjectingA2AHttpClient http = assertInstanceOf(HeaderInjectingA2AHttpClient.class,
                    client.createHttpClient(registry.get("remote").orElseThrow()));
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

    @ParameterizedTest
    @ValueSource(strings = {"", "card-default"})
    void authenticatorTargetMatchesFirstJsonRpcInterfaceAndActualUrl(String tenant) throws Exception {
        String endpoint = startServer();
        String expectedUrl = endpoint + (tenant.isEmpty() ? "" : "/" + tenant);
        List<ExternalTargetRef> targets = new CopyOnWriteArrayList<>();
        ExternalAuthenticator authenticator = (target, config) -> {
            targets.add(target);
            assertThat(target.url()).isEqualTo(expectedUrl);
            return new AuthMaterial(Map.of("Authorization", "Bearer jsonrpc-test"), Map.of(), Map.of());
        };
        contextRunner.withBean(ExternalAuthenticator.class, () -> authenticator).run(context -> {
            AgentCard card = card(List.of(new AgentInterface("GRPC", endpoint + "/grpc", null, "1.0"),
                    new AgentInterface("JSONRPC", endpoint + "/", tenant, "1.0"),
                    new AgentInterface("JSONRPC", endpoint + "/unused", null, "1.0")));
            context.getBean(A2ARemoteAgentCardRegistry.class).register("remote", card, 5, true);
            A2ARemoteAgentClient client = context.getBean(A2ARemoteAgentClient.class);

            invoke(client, "remote", false);
            invoke(client, "remote", true);

            assertThat(targets).hasSize(2);
            assertThat(requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.path()).isEqualTo(java.net.URI.create(expectedUrl).getPath());
                assertThat(request.authorization()).isEqualTo("Bearer jsonrpc-test");
            });
        });
    }

    @Test
    void incompatibleCardFailsBeforePreparingAuthentication() throws Exception {
        String endpoint = startServer();
        List<ExternalTargetRef> targets = new CopyOnWriteArrayList<>();
        ExternalAuthenticator authenticator = (target, config) -> {
            targets.add(target);
            return AuthMaterial.none();
        };
        contextRunner.withBean(ExternalAuthenticator.class, () -> authenticator).run(context -> {
            AgentCard card = card(List.of(new AgentInterface("GRPC", endpoint, null, "1.0")));
            context.getBean(A2ARemoteAgentCardRegistry.class).register("remote", card, 5, false);

            assertThatThrownBy(() -> invoke(context.getBean(A2ARemoteAgentClient.class), "remote", false))
                    .isInstanceOf(A2AClientException.class).hasMessage("No compatible transport found");
            assertThat(targets).isEmpty();
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
        boolean isStreaming = "SendStreamingMessage".equals(request.get("method").getAsString());
        String wire = isStreaming ? "data: " + response + "\n\n" : response.toString();
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", isStreaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void invoke(A2ARemoteAgentClient client, String name, boolean isStreaming) throws Exception {
        RemoteCall call = new RemoteCall(name, "hello", "ctx", null, Map.of(), Map.of(), isStreaming);
        RemoteCallOutcome outcome = client.callOutcome(call, mock(RemoteAgentCaller.EventObserver.class))
                .get(10, TimeUnit.SECONDS);
        assertThat(outcome.result()).isEqualTo("ok");
    }

    static AgentCard card(String endpoint, String tenant) {
        return card(List.of(new AgentInterface("JSONRPC", endpoint, tenant, "1.0")));
    }

    private static AgentCard card(List<AgentInterface> interfaces) {
        return AgentCard.builder().name("remote").description("remote").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Map.of())
                .securityRequirements(List.of())
                .supportedInterfaces(interfaces).url(interfaces.get(0).url())
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    private record ReceivedRequest(String path, String authorization, JsonObject body) {
    }
}
