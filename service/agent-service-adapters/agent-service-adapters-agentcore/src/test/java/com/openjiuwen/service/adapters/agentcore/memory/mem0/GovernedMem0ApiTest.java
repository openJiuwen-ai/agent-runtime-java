/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock-server integration test for {@link GovernedMem0Api} covering the mem0 request contract and
 * the runtime governance (timeout/retry/circuit-breaker/audit).
 *
 * @since 2026-07-07
 */
class GovernedMem0ApiTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_KEY = "super-secret-key";

    private HttpServer server;

    private final List<RecordedRequest> requests = new ArrayList<>();

    private volatile int status = 200;

    private volatile String responseBody = "{\"results\": []}";

    private volatile long sleepMillis = 0L;

    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bodyBytes;
        try (InputStream in = exchange.getRequestBody()) {
            bodyBytes = in.readAllBytes();
        }
        requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"), new String(bodyBytes, StandardCharsets.UTF_8)));
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private MiddlewareProperties.Memory basePolicy() {
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setTimeoutMs(2000);
        memory.getRetry().setMax(0);
        memory.getRetry().setBackoffMs(0);
        memory.getCircuitBreaker().setEnabled(false);
        memory.getAudit().setEnabled(true);
        return memory;
    }

    @Test
    void addSearchGetDeleteUseExpectedMethodPathHeaderAndBody() throws Exception {
        GovernedMem0Api api = new GovernedMem0Api(baseUrl, basePolicy());

        responseBody = "{\"results\": []}";
        api.addMemories(baseUrl, API_KEY,
            List.of(Map.of("role", "user", "content", "hi")), Map.of("user_id", "u1", "agent_id", "a1"), true);
        RecordedRequest add = requests.get(0);
        assertThat(add.method).isEqualTo("POST");
        assertThat(add.path).isEqualTo("/v3/memories/add/");
        assertThat(add.authorization).isEqualTo("Token " + API_KEY);
        assertThat(add.body).contains("\"messages\"").contains("\"user_id\":\"u1\"").contains("\"agent_id\":\"a1\"");

        responseBody = "{\"results\": [{\"id\":\"m-1\",\"memory\":\"likes coffee\",\"score\":0.9}]}";
        List<Map<String, Object>> results = api.searchMemories(baseUrl, API_KEY, "coffee",
            Map.of("user_id", "u1"), true, 5);
        RecordedRequest search = requests.get(1);
        assertThat(search.method).isEqualTo("POST");
        assertThat(search.path).isEqualTo("/v3/memories/search/");
        assertThat(search.body).contains("\"query\":\"coffee\"").contains("\"top_k\":5").contains("\"rerank\":true");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("id", "m-1").containsEntry("score", 0.9);

        responseBody = "{\"id\":\"m-1\",\"memory\":\"likes coffee\"}";
        Map<String, Object> single = api.getMemory(baseUrl, API_KEY, "m-1");
        RecordedRequest get = requests.get(2);
        assertThat(get.method).isEqualTo("GET");
        assertThat(get.path).isEqualTo("/v1/memories/m-1/");
        assertThat(single).containsEntry("id", "m-1");

        status = 204;
        responseBody = "";
        api.deleteMemory(baseUrl, API_KEY, "m-1");
        RecordedRequest delete = requests.get(3);
        assertThat(delete.method).isEqualTo("DELETE");
        assertThat(delete.path).isEqualTo("/v1/memories/m-1/");
    }

    @Test
    void timeoutTriggersExternalCallException() {
        MiddlewareProperties.Memory memory = basePolicy();
        memory.setTimeoutMs(150);
        sleepMillis = 800L;
        GovernedMem0Api api = new GovernedMem0Api(baseUrl, memory);

        assertThatThrownBy(() -> api.getMemory(baseUrl, API_KEY, "m-1"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void retryRepeatsAccordingToPolicy() throws Exception {
        MiddlewareProperties.Memory memory = basePolicy();
        memory.getRetry().setMax(1);
        AtomicInteger calls = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            int attempt = calls.incrementAndGet();
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] out;
            if (attempt == 1) {
                out = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, out.length);
            } else {
                out = "{\"results\": []}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
            }
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        GovernedMem0Api api = new GovernedMem0Api(baseUrl, memory);

        api.addMemories(baseUrl, API_KEY, List.of(Map.of("role", "user", "content", "hi")), Map.of(), true);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void circuitBreakerOpensAfterConsecutiveFailures() {
        MiddlewareProperties.Memory memory = basePolicy();
        memory.getCircuitBreaker().setEnabled(true);
        memory.getCircuitBreaker().setFailureThreshold(2);
        memory.getCircuitBreaker().setResetTimeoutMs(120000L);
        status = 500;
        responseBody = "{\"error\":\"boom\"}";
        GovernedMem0Api api = new GovernedMem0Api(baseUrl, memory);

        assertThatThrownBy(() -> api.getMemory(baseUrl, API_KEY, "m-1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> api.getMemory(baseUrl, API_KEY, "m-1")).isInstanceOf(RuntimeException.class);
        int hitsBeforeOpen = requests.size();
        assertThatThrownBy(() -> api.getMemory(baseUrl, API_KEY, "m-1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("circuit breaker is open");
        assertThat(requests.size()).isEqualTo(hitsBeforeOpen);
    }

    @Test
    void auditLogNeverContainsPlaintextApiKey() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger(ExternalCallExecutor.class);
        auditLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            GovernedMem0Api api = new GovernedMem0Api(baseUrl, basePolicy());
            responseBody = "{\"results\": []}";
            api.addMemories(baseUrl, API_KEY, List.of(Map.of("role", "user", "content", "hi")), Map.of(), true);

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(msg -> msg.contains("EXTERNAL_CALL_AUDIT"));
            assertThat(messages).noneMatch(msg -> msg.contains(API_KEY));
        } finally {
            auditLogger.detachAppender(appender);
        }
    }

    private record RecordedRequest(String method, String path, String authorization, String body) {
    }
}
