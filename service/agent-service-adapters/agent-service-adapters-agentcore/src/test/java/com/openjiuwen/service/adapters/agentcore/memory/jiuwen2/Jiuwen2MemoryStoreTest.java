/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen2;

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
 * Tests the runtime agent-memory 2.0 {@link com.openjiuwen.service.adapters.common.memory.MemoryStore}
 * implementation against a fake speaking the verified 2.0 contract.
 *
 * @since 0.1.0
 */
class Jiuwen2MemoryStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalJiuwen2Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private Jiuwen2MemoryStore newStore() {
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEndpoint(server.endpoint());
        memory.setScopeOrg("local");
        memory.setScopeUser("developer");
        return new Jiuwen2MemoryStore("plainkey", memory,
            new Jiuwen2MemoryApi(server.endpoint(), memory, "plainkey"));
    }

    @Test
    void addMergesTurnIntoSingleMarkedContentWithIdentityBoundScope() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        MemoryScope requestScope = new MemoryScope("biz-user", "biz-agent", "sess-1", "biz-space");
        MemoryWriteResult added = store.add(new MemoryAddRequest(
            requestScope,
            List.of(new MemoryMessage("user", "我叫小王，喜欢拿铁"),
                new MemoryMessage("assistant", "好的，已记住你的偏好")),
            Map.of("infer", true)));

        assertThat(added.records()).hasSize(1);
        assertThat(added.records().get(0).memoryId()).isEqualTo("unit-1");
        assertThat(server.lastAddBody())
            .containsEntry("content", "[user] 我叫小王，喜欢拿铁\n[assistant] 好的，已记住你的偏好");
        assertThat(server.lastAddScope())
            .containsEntry("org", "local")
            .containsEntry("space", "")
            .containsEntry("user", "developer")
            .containsEntry("agent", "")
            .containsEntry("session", "sess-1");
        assertThat(server.lastAddUserMetadata())
            .containsEntry("biz_user_id", "biz-user")
            .containsEntry("biz_agent_id", "biz-agent")
            .containsEntry("biz_scope_id", "biz-space");
        // 2.0 rejects unknown payload fields: the 1.0-era messages/user_id/scope_id must
        // never be sent.
        assertThat(server.lastAddBody()).doesNotContainKey("messages");
        assertThat(server.lastAddBody()).doesNotContainKey("user_id");
        assertThat(server.lastAddBody()).doesNotContainKey("scope_id");
    }

    @Test
    void addSkipsBlankMessagesAndDefaultsBlankRoleToUser() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.add(new MemoryAddRequest(MemoryScope.empty(),
            List.of(new MemoryMessage("", "第一条"), new MemoryMessage("assistant", "  ")), Map.of()));

        assertThat(server.lastAddBody()).containsEntry("content", "[user] 第一条");
    }

    @Test
    void searchSendsExplicitL2DisclosureAndContextScope() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        List<MemoryRecord> found = store.search(new MemorySearchRequest(
            new MemoryScope("", "", "sess-9", ""), "拿铁偏好", 3, true, Map.of()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).memoryId()).isEqualTo("unit-1");
        assertThat(found.get(0).memory()).isEqualTo("用户喜欢拿铁");
        assertThat(found.get(0).metadata()).containsEntry("score", 0.4);
        assertThat(found.get(0).raw()).containsEntry("unit_id", "unit-1");
        assertThat(server.lastSearchBody())
            .containsEntry("query", "拿铁偏好")
            .containsEntry("top_k", 3)
            .containsEntry("disclosure", "l2");
        assertThat(server.lastSearchContextScope())
            .containsEntry("org", "local")
            .containsEntry("user", "developer")
            .containsEntry("session", "sess-9");
    }

    @Test
    void topKUsesDefaultAndUpperBound() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.search(new MemorySearchRequest(MemoryScope.empty(), "query", 0, null, Map.of()));
        assertThat(server.lastSearchBody()).containsEntry("top_k", 10);

        store.search(new MemorySearchRequest(MemoryScope.empty(), "query", 99, null, Map.of()));
        assertThat(server.lastSearchBody()).containsEntry("top_k", 50);
    }

    @Test
    void blankQueryReturnsEmptyAndSkipsApi() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        assertThat(store.search(new MemorySearchRequest(MemoryScope.empty(), " ", 3, null, Map.of())))
            .isEmpty();
        assertThat(server.searchRequests()).isZero();
    }

    @Test
    void getResolvesContentFromUnitSegments() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        assertThat(store.get(new MemoryGetRequest(MemoryScope.empty(), "unit-1")))
            .hasValueSatisfying(record -> {
                assertThat(record.memoryId()).isEqualTo("unit-1");
                assertThat(record.memory()).isEqualTo("hello memory test one");
            });

        assertThat(server.lastGetBody()).containsEntry("unit_id", "unit-1");
        assertThat(server.lastGetScope()).containsEntry("org", "local");
    }

    @Test
    void getUnknownIdReturnsEmpty() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        assertThat(store.get(new MemoryGetRequest(MemoryScope.empty(), "missing"))).isEmpty();
    }

    @Test
    void blankGetIdReturnsEmpty() {
        Jiuwen2MemoryStore store = new Jiuwen2MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThat(store.get(new MemoryGetRequest(MemoryScope.empty(), " "))).isEmpty();
    }

    @Test
    void deleteSendsSelectorAndScopedScope() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.delete(new MemoryDeleteRequest(MemoryScope.empty(), "unit-1"));

        assertThat(server.lastDeleteBody()).containsEntry("unit_ids", List.of("unit-1"));
        assertThat(server.lastDeleteBody()).containsKey("scope");
    }

    @Test
    void blankDeleteIdThrows() {
        Jiuwen2MemoryStore store = new Jiuwen2MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThatThrownBy(() -> store.delete(new MemoryDeleteRequest(MemoryScope.empty(), " ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("memory_id must not be blank");
    }

    @Test
    void emptyAddMessagesThrow() {
        Jiuwen2MemoryStore store = new Jiuwen2MemoryStore("plainkey", new MiddlewareProperties.Memory(), null);

        assertThatThrownBy(() -> store.add(new MemoryAddRequest(MemoryScope.empty(), List.of(), Map.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("memory add messages must not be empty");
    }

    private static final class LocalJiuwen2Server {
        private final HttpServer server;

        private final AtomicInteger searchRequests = new AtomicInteger(0);

        private final AtomicReference<Map<String, Object>> lastAddBody = new AtomicReference<>(Map.of());

        private final AtomicReference<Map<String, Object>> lastSearchBody = new AtomicReference<>(Map.of());

        private final AtomicReference<Map<String, Object>> lastGetBody = new AtomicReference<>(Map.of());

        private final AtomicReference<Map<String, Object>> lastDeleteBody = new AtomicReference<>(Map.of());

        private LocalJiuwen2Server(HttpServer server) {
            this.server = server;
        }

        private static LocalJiuwen2Server start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LocalJiuwen2Server localServer = new LocalJiuwen2Server(server);
                server.createContext("/", localServer::handle);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start local jiuwen2 server", ex);
            }
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Map<String, Object> lastAddBody() {
            return lastAddBody.get();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastAddScope() {
            return scopeOf(lastAddBody.get());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastAddUserMetadata() {
            Object metadata = lastAddBody.get().get("user_metadata");
            return metadata instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private Map<String, Object> lastSearchBody() {
            return lastSearchBody.get();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastSearchContextScope() {
            Object context = lastSearchBody.get().get("context");
            return context instanceof Map<?, ?> map ? scopeOf((Map<String, Object>) map) : Map.of();
        }

        private Map<String, Object> lastGetBody() {
            return lastGetBody.get();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastGetScope() {
            return scopeOf(lastGetBody.get());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastDeleteBody() {
            Object selector = lastDeleteBody.get().get("selector");
            return selector instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private int searchRequests() {
            return searchRequests.get();
        }

        private static Map<String, Object> scopeOf(Map<String, Object> body) {
            Object scope = body.get("scope");
            return scope instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private void stop() {
            server.stop(0);
        }

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/add".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                lastAddBody.set(body);
                writeJson(exchange, 200, List.of(memoryUnit(String.valueOf(body.get("content")))));
                return;
            }
            if ("POST".equals(method) && "/v1/search".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                searchRequests.incrementAndGet();
                lastSearchBody.set(body);
                writeJson(exchange, 200, Map.of("items", List.of(searchItem()), "trajectory", List.of(),
                    "errors", List.of()));
                return;
            }
            if ("POST".equals(method) && "/v1/get".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                lastGetBody.set(body);
                if ("unit-1".equals(body.get("unit_id"))) {
                    writeJson(exchange, 200, memoryUnit("hello memory test one"));
                } else {
                    writeJson(exchange, 404,
                        Map.of("error", "NotFoundError", "message", "resource not found", "retryable", false));
                }
                return;
            }
            if ("POST".equals(method) && "/v1/delete".equals(path)) {
                Map<String, Object> body = readBody(exchange);
                lastDeleteBody.set(body);
                writeJson(exchange, 200, List.of("unit-1"));
                return;
            }
            if ("GET".equals(method) && "/healthz".equals(path)) {
                writeJson(exchange, 200, Map.of("status", "ok", "profile", "offline"));
                return;
            }
            writeJson(exchange, 404, Map.of("error", "NotFound", "message", method + " " + path + " not found"));
        }

        private static Map<String, Object> memoryUnit(String content) {
            return Map.of(
                "id", "unit-1",
                "scope", Map.of("org", "local", "space", "", "user", "developer", "agent", "", "session", ""),
                "tier", "episodic",
                "layers", Map.of("l0", "", "l1", ""),
                "segments", List.of(Map.of("content", content, "assets", List.of(), "source", "text")),
                "user_metadata", Map.of(),
                "lifecycle", "active");
        }

        private static Map<String, Object> searchItem() {
            return Map.of(
                "unit_id", "unit-1",
                "score", 0.4,
                "abstract", "用户喜欢拿铁",
                "overview", "用户喜欢拿铁",
                "content", "用户喜欢拿铁",
                "user_metadata", Map.of(),
                "level", "l2",
                "system_metadata", Map.of());
        }

        private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return MAPPER.readValue(input, Map.class);
            }
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
