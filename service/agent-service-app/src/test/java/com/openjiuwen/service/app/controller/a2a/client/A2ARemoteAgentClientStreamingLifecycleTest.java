/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Regression tests for the SDK's null-on-EOF streaming callback contract.
 *
 * @since 0.1.0
 */
class A2ARemoteAgentClientStreamingLifecycleTest {
    private static final QueryStreamObserver NOOP_OBSERVER = new QueryStreamObserver() {
        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void serverEofBeforeTerminalEventFailsPromptly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", this::closeEmptyEventStream);
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("remote", testCard(endpoint), 30, true);
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry);

        var result = client.callOutcome(
                new RemoteCall("remote", "hello", "ctx", null, Map.of(), Map.of(), true),
                NOOP_OBSERVER, null);

        Throwable thrown = catchThrowable(() -> result.get(5, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOfSatisfying(ExecutionException.class,
                executionException -> assertThat(executionException.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed the stream before a terminal event"));
    }

    private void closeEmptyEventStream(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
        exchange.close();
    }

    private static AgentCard testCard(String endpoint) {
        return AgentCard.builder().name("remote").description("remote").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Collections.emptyMap())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint, null, "1.0"))).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
