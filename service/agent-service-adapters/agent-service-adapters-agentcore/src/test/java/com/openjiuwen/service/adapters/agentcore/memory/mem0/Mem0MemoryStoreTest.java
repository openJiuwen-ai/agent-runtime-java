/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryMessage;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the runtime mem0 {@link com.openjiuwen.service.adapters.common.memory.MemoryStore}
 * implementation.
 *
 * @since 0.1.0
 */
class Mem0MemoryStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalMem0Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void addSearchGetDeleteUseGovernedMem0ApiAndPreserveMemoryId() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        memory.setRerank(false);

        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));

        MemoryScope requestScope = new MemoryScope("request-user", "", "", "");
        MemoryWriteResult added = store.add(new MemoryAddRequest(
            requestScope,
            List.of(new MemoryMessage("user", "用户喜欢拿铁")),
            Map.of("infer", false)));

        assertThat(added.records()).hasSize(1);
        assertThat(added.records().get(0).memoryId()).isEqualTo("mem-1");
        assertThat(server.lastAddBody())
            .containsEntry("user_id", "request-user")
            .containsEntry("infer", false);
        assertThat(server.lastAddBody()).doesNotContainKey("agent_id");

        List<MemoryRecord> found = store.search(new MemorySearchRequest(
            requestScope,
            "咖啡偏好",
            3,
            true,
            Map.of()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).memoryId()).isEqualTo("mem-1");
        assertThat(found.get(0).memory()).isEqualTo("用户喜欢拿铁");
        assertThat(found.get(0).raw()).containsEntry("memory_id", "mem-1");
        assertThat(server.lastSearchBody())
            .containsEntry("query", "咖啡偏好")
            .containsEntry("top_k", 3)
            .containsEntry("rerank", true);
        assertThat(server.lastSearchFilters())
            .containsEntry("user_id", "request-user");
        assertThat(server.lastSearchFilters()).doesNotContainKey("agent_id");

        assertThat(store.get(new MemoryGetRequest(requestScope, "mem-1")))
            .hasValueSatisfying(record -> assertThat(record.memory()).isEqualTo("用户喜欢拿铁"));

        store.delete(new MemoryDeleteRequest(requestScope, "mem-1"));

        assertThat(server.lastDeletedMemoryId()).isEqualTo("mem-1");
    }

    @Test
    void explicitAgentIdIsSentWhenProvidedByRequestScope() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());

        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));

        MemoryScope requestScope = new MemoryScope("request-user", "request-agent", "", "");
        store.search(new MemorySearchRequest(requestScope, "咖啡偏好", 3, false, Map.of()));

        assertThat(server.lastSearchFilters())
            .containsEntry("user_id", "request-user")
            .containsEntry("agent_id", "request-agent");
    }

    @Test
    void blankQueryReturnsEmptyAndSkipsApi() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));

        List<MemoryRecord> records = store.search(new MemorySearchRequest(
            new MemoryScope("request-user", "", "", ""), " ", 3, false, Map.of()));

        assertThat(records).isEmpty();
        assertThat(server.searchRequests()).isZero();
    }

    @Test
    void topKUsesDefaultAndUpperBound() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));
        MemoryScope requestScope = new MemoryScope("request-user", "", "", "");

        store.search(new MemorySearchRequest(requestScope, "咖啡偏好", 0, false, Map.of()));
        assertThat(server.lastSearchBody()).containsEntry("top_k", 10);

        store.search(new MemorySearchRequest(requestScope, "咖啡偏好", 99, false, Map.of()));
        assertThat(server.lastSearchBody()).containsEntry("top_k", 50);
    }

    @Test
    void longQueryPassesThroughSearchUnchanged() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));
        String longQuery = "用户想查询一段很长的长期记忆内容。".repeat(300);

        store.search(new MemorySearchRequest(new MemoryScope("request-user", "", "", ""),
            longQuery, 3, false, Map.of()));

        assertThat(server.lastSearchBody()).containsEntry("query", longQuery);
    }

    @Test
    void longAddMessagePassesThroughAddUnchanged() {
        server = LocalMem0Server.start();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", memory,
            new GovernedMem0Api(server.endpoint(), memory));
        String longContent = "用户描述了一段很长的偏好和事实。".repeat(300);

        store.add(new MemoryAddRequest(new MemoryScope("request-user", "", "", ""),
            List.of(new MemoryMessage("user", longContent)), Map.of()));

        assertThat(messages(server.lastAddBody())).anySatisfy(message -> assertThat(message)
            .containsEntry("role", "user")
            .containsEntry("content", longContent));
    }

    @Test
    void blankGetIdReturnsEmpty() {
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThat(store.get(new MemoryGetRequest(MemoryScope.empty(), " "))).isEmpty();
    }

    @Test
    void blankDeleteIdThrows() {
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThatThrownBy(() -> store.delete(new MemoryDeleteRequest(MemoryScope.empty(), " ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("memory_id must not be blank");
    }

    @Test
    void emptyAddMessagesThrow() {
        Mem0MemoryStore store = new Mem0MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThatThrownBy(() -> store.add(new MemoryAddRequest(MemoryScope.empty(), List.of(), Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("memory add messages must not be empty");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> body) {
        Object messages = body.get("messages");
        return messages instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static final class LocalMem0Server {
        private final HttpServer server;

        private final AtomicInteger searchRequests = new AtomicInteger(0);

        private final AtomicReference<Map<String, Object>> lastAddBody = new AtomicReference<>(Map.of());

        private final AtomicReference<Map<String, Object>> lastSearchBody = new AtomicReference<>(Map.of());

        private final AtomicReference<String> lastDeletedMemoryId = new AtomicReference<>("");

        private LocalMem0Server(HttpServer server) {
            this.server = server;
        }

        private static LocalMem0Server start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LocalMem0Server localServer = new LocalMem0Server(server);
                server.createContext("/", localServer::handle);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start local mem0 server", ex);
            }
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Map<String, Object> lastAddBody() {
            return lastAddBody.get();
        }

        private Map<String, Object> lastSearchBody() {
            return lastSearchBody.get();
        }

        private int searchRequests() {
            return searchRequests.get();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastSearchFilters() {
            Object filters = lastSearchBody.get().get("filters");
            return filters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private String lastDeletedMemoryId() {
            return lastDeletedMemoryId.get();
        }

        private void stop() {
            server.stop(0);
        }

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v3/memories/add/".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                lastAddBody.set(body);
                writeJson(exchange, 200, Map.of("results", List.of(memoryRecord())));
                return;
            }
            if ("POST".equals(method) && "/v3/memories/search/".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                searchRequests.incrementAndGet();
                lastSearchBody.set(body);
                writeJson(exchange, 200, Map.of("results", List.of(memoryRecord())));
                return;
            }
            if ("GET".equals(method) && "/v1/memories/mem-1/".equals(path)) {
                writeJson(exchange, 200, memoryRecord());
                return;
            }
            if ("DELETE".equals(method) && path.startsWith("/v1/memories/") && path.endsWith("/")) {
                lastDeletedMemoryId.set(path.substring("/v1/memories/".length(), path.length() - 1));
                writeBytes(exchange, 204, new byte[0]);
                return;
            }
            writeJson(exchange, 404, Map.of("message", method + " " + path + " not found"));
        }

        private static Map<String, Object> memoryRecord() {
            return Map.of(
                "memory_id", "mem-1",
                "memory", "用户喜欢拿铁",
                "metadata", Map.of("source", "test"));
        }

        private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return MAPPER.readValue(input, Map.class);
            }
        }

        private static void writeBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            }
            exchange.close();
        }

        private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        }
    }
}
