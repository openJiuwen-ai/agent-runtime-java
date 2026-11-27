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
import com.openjiuwen.service.adapters.common.memory.MemorySearchObserver;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.ObservedMemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
        return newStore(0);
    }

    private Jiuwen2MemoryStore newStore(int retries) {
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.getRetry().setMax(retries);
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
            List.of(new MemoryMessage("", "第一条"), new MemoryMessage("assistant", "  "),
                new MemoryMessage("assistant", "第二条")),
            Map.of()));

        assertThat(server.lastAddBody()).containsEntry("content", "[user] 第一条\n[assistant] 第二条");
    }

    @Test
    void addSingleMessageKeepsContentVerbatim() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        // 单条消息（例如 memory_add 工具写入）不需要角色标记：标记会写进正文、进
        // embedding 与后续注入块，属于无意义污染。
        store.add(new MemoryAddRequest(MemoryScope.empty(),
            List.of(new MemoryMessage("user", "用户下周三去北京出差")), Map.of()));

        assertThat(server.lastAddBody()).containsEntry("content", "用户下周三去北京出差");
    }

    @Test
    void addForwardsInferOptionAsSystemMetadataTrue() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.add(new MemoryAddRequest(MemoryScope.empty(),
            List.of(new MemoryMessage("user", "我今天中午吃了麦当劳")), Map.of("infer", true)));

        // 2.0 的抽取链路（矛盾更新/supersede）由 system_metadata["infer"]="true" 触发，
        // 写入路径只认字符串 "true"（_truthy_metadata）；boolean option 必须转成该字符串
        // 下发，否则 derive 上下文传不到引擎，旧事实压过新事实（issue #124）。
        assertThat(server.lastAddSystemMetadata()).containsEntry("infer", "true");
    }

    @Test
    void addWithoutInferOptionOmitsSystemMetadata() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.add(new MemoryAddRequest(MemoryScope.empty(),
            List.of(new MemoryMessage("user", "普通记录")), Map.of()));

        assertThat(server.lastAddBody()).doesNotContainKey("system_metadata");
    }

    @Test
    void addWithFalseInferOptionOmitsSystemMetadata() {
        server = LocalJiuwen2Server.start();
        Jiuwen2MemoryStore store = newStore();

        store.add(new MemoryAddRequest(MemoryScope.empty(),
            List.of(new MemoryMessage("user", "普通记录")), Map.of("infer", false)));

        assertThat(server.lastAddBody()).doesNotContainKey("system_metadata");
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

    @Test
    void observedRetryPropagatesPerAttemptAndReportsOnlyAcceptedEnvelope() throws Exception {
        server = LocalJiuwen2Server.start();
        server.shouldFailFirst = true;
        Thread caller = Thread.currentThread();
        AtomicReference<String> raw = new AtomicReference<>();
        AtomicInteger accepted = new AtomicInteger();
        MemoryStore store = ObservedMemoryStore.wrap(newStore(1), (provider, query, topK) -> {
            assertThat(Thread.currentThread()).isEqualTo(caller);
            assertThat(provider).isEqualTo("jiuwen2");
            return new MemorySearchObserver.Call() {
                @Override
                public MemorySearchObserver.TraceHeaders startAttempt(int attempt) {
                    assertThat(Thread.currentThread()).isEqualTo(caller);
                    return new MemorySearchObserver.TraceHeaders(
                        "00-11111111111111111111111111111111-000000000000000" + attempt + "-01", "vendor=test");
                }

                @Override
                public void rawResponse(int attempt, String json) {
                    assertThat(Thread.currentThread()).isEqualTo(caller);
                    assertThat(raw.getAndSet(json)).isNull();
                    accepted.set(attempt);
                }

                @Override
                public void succeeded(List<MemoryRecord> records) {
                    throw new IllegalStateException("observer failure must not retry the search");
                }
            };
        });
        assertThat(store.search(new MemorySearchRequest(null, "query", 3, null, null))).hasSize(1);
        assertThat(accepted).hasValue(2);
        assertThat(server.searchRequests()).isEqualTo(2);
        assertThat(server.lastSearchHeaders.get().getFirst("traceparent")).endsWith("0000000000000002-01");
        assertThat(server.lastSearchHeaders.get().getFirst("tracestate")).isEqualTo("vendor=test");
        assertThat(server.lastSearchHeaders.get().getFirst("Authorization")).isEqualTo("Bearer plainkey");
        assertThat(MAPPER.readTree(raw.get()).has("trajectory")).isTrue();
        assertThat(MAPPER.readTree(raw.get()).at("/items/0/unit_id").asText()).isEqualTo("unit-1");
    }

    @Test
    void directTransportObserverFailuresNeitherRetryNorReplaceResult() {
        server = LocalJiuwen2Server.start();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        MemorySearchObserver.Call broken = new MemorySearchObserver.Call() {
            @Override
            public MemorySearchObserver.TraceHeaders startAttempt(int attempt) {
                starts.incrementAndGet();
                throw new IllegalStateException("start failed");
            }

            @Override
            public void endAttempt(int attempt, boolean isCompleted) {
                assertThat(isCompleted).isTrue();
                ends.incrementAndGet();
                throw new IllegalStateException("finish failed");
            }

            @Override
            public void rawResponse(int attempt, String json) {
                throw new IllegalStateException("capture failed");
            }
        };
        assertThat(newStore(1).search(searchFor("direct"), broken)).hasSize(1);
        assertThat(starts).hasValue(1);
        assertThat(ends).hasValue(1);
        assertThat(server.searchRequests()).isOne();
    }

    @Test
    void overlappingSearchesKeepCallerHeadersBoundToTheirQuery() throws Exception {
        server = LocalJiuwen2Server.start();
        CountDownLatch bothWorkers = new CountDownLatch(2);
        String prefix = "00-11111111111111111111111111111111-000000000000000";
        MemorySearchObserver observer = (provider, query, topK) -> new MemorySearchObserver.Call() {
            @Override
            public MemorySearchObserver.TraceHeaders startAttempt(int attempt) {
                bothWorkers.countDown();
                await(bothWorkers);
                return new MemorySearchObserver.TraceHeaders(prefix + query + "-01", null);
            }
        };
        MemoryStore store = ObservedMemoryStore.wrap(newStore(), observer);
        ExecutorService callers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2));
        try {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> store.search(searchFor("1")), callers);
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> store.search(searchFor("2")), callers);
            CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }
        assertThat(server.parentsByQuery).containsEntry("1", prefix + "1-01").containsEntry("2", prefix + "2-01");
    }

    @Test
    void timedOutAttemptCannotPublishRawResponseOrChangeFailure() throws Exception {
        server = LocalJiuwen2Server.start();
        MiddlewareProperties.Memory policy = new MiddlewareProperties.Memory();
        policy.setEndpoint(server.endpoint());
        policy.setTimeoutMs(550);
        CountDownLatch release = new CountDownLatch(1);
        server.searchGate = release;
        AtomicInteger rawCount = new AtomicInteger();
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        AtomicReference<String> failureCode = new AtomicReference<>();
        MemoryStore store = ObservedMemoryStore.wrap(new Jiuwen2MemoryStore("key", policy, null),
            (provider, query, topK) -> new MemorySearchObserver.Call() {
                @Override
                public void endAttempt(int attempt, boolean isCompleted) {
                    accepted.set(isCompleted);
                }

                @Override
                public void rawResponse(int acceptedAttempt, String json) {
                    rawCount.incrementAndGet();
                }

                @Override
                public void failed(String code) {
                    failureCode.set(code);
                    throw new IllegalStateException("observer must not replace business error");
                }
            });
        try {
            assertThatThrownBy(() -> store.search(searchFor("late")))
                .isInstanceOf(com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException.class);
        } finally {
            release.countDown();
        }
        assertThat(accepted.get()).isFalse();
        assertThat(failureCode.get()).isNotBlank();
        assertThat(rawCount).hasValue(0);
    }

    @Test
    void slowCompletionCannotRetryOrTimeoutAnAcceptedResponse() {
        server = LocalJiuwen2Server.start();
        MiddlewareProperties.Memory policy = new MiddlewareProperties.Memory();
        policy.setEndpoint(server.endpoint());
        policy.setTimeoutMs(600);
        policy.getRetry().setMax(1);
        MemoryStore store = ObservedMemoryStore.wrap(new Jiuwen2MemoryStore("key", policy, null),
            (provider, query, requested) -> new MemorySearchObserver.Call() {
                @Override
                public void endAttempt(int attempt, boolean isCompleted) {
                    assertThat(isCompleted).isTrue();
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                }
            });
        assertThat(store.search(searchFor("slow-observer"))).hasSize(1);
        assertThat(server.searchRequests()).isOne();
    }

    @Test
    void observedCustomClientKeepsOverride() {
        server = LocalJiuwen2Server.start();
        MiddlewareProperties.Memory policy = new MiddlewareProperties.Memory();
        policy.setEndpoint(server.endpoint());
        AtomicInteger invocations = new AtomicInteger();
        Jiuwen2MemoryApi custom = new Jiuwen2MemoryApi(server.endpoint(), policy, "test") {
            @Override
            public List<Map<String, Object>> search(String url, String query, Map<String, Object> scope, int k) {
                invocations.incrementAndGet();
                return List.of(Map.of("unit_id", "custom", "content", "from override"));
            }
        };
        MemoryStore plain = new Jiuwen2MemoryStore("key", policy, custom);
        MemoryStore observed = ObservedMemoryStore.wrap(plain, (provider, query, k) -> new MemorySearchObserver.Call() {
            @Override
            public void rawResponse(int attempt, String json) {
                throw new AssertionError("no raw envelope from custom client");
            }
        });
        assertThat(observed.search(searchFor("q"))).isEqualTo(plain.search(searchFor("q")));
        assertThat(invocations).hasValue(2);
        assertThat(server.searchRequests()).isZero();
    }

    @Test
    void observerFailureDoesNotReplaceProviderException() {
        server = LocalJiuwen2Server.start();
        MiddlewareProperties.Memory policy = new MiddlewareProperties.Memory();
        policy.setEndpoint(server.endpoint());
        ArithmeticException providerFailure = new ArithmeticException("provider failure");
        Jiuwen2MemoryApi custom = new Jiuwen2MemoryApi(server.endpoint(), policy, "test") {
            @Override
            public List<Map<String, Object>> search(String url, String query, Map<String, Object> scope, int topK) {
                throw providerFailure;
            }
        };
        AtomicReference<String> observedCode = new AtomicReference<>();
        MemorySearchObserver observer = (provider, query, depth) -> new MemorySearchObserver.Call() {
            @Override
            public void failed(String code) {
                observedCode.set(code);
                throw new UnsupportedOperationException("observer failure");
            }
        };
        MemoryStore store = ObservedMemoryStore.wrap(new Jiuwen2MemoryStore("key", policy, custom), observer);
        assertThatThrownBy(() -> store.search(searchFor("q"))).isSameAs(providerFailure);
        assertThat(observedCode.get()).isEqualTo("ArithmeticException");
    }

    @Test
    void observerErrorsAreNotSwallowed() {
        server = LocalJiuwen2Server.start();
        AssertionError fatal = new AssertionError("observer error");
        MemoryStore store = ObservedMemoryStore.wrap(newStore(), (provider, query, depth) -> {
            throw fatal;
        });
        assertThatThrownBy(() -> store.search(searchFor("q"))).isSameAs(fatal);
        assertThat(server.searchRequests()).isZero();
    }

    @Test
    void blankSearchSkipsWhileEmptyResponseReportsDepth() {
        server = LocalJiuwen2Server.start();
        server.isEmptySearch = true;
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger effective = new AtomicInteger();
        MemoryStore store = ObservedMemoryStore.wrap(newStore(), (provider, query, requested) -> {
            assertThat(requested).isEqualTo(100);
            return new MemorySearchObserver.Call() {
                @Override
                public void skipped() {
                    skipped.incrementAndGet();
                }

                @Override
                public void effectiveTopK(int depth) {
                    effective.set(depth);
                }

                @Override
                public void succeeded(List<MemoryRecord> records) {
                    assertThat(records).isEmpty();
                    successes.incrementAndGet();
                }
            };
        });
        assertThat(store.search(new MemorySearchRequest(null, " ", 100, null, null))).isEmpty();
        assertThat(server.searchRequests()).isZero();
        assertThat(skipped).hasValue(1);
        assertThat(successes).hasValue(0);
        assertThat(store.search(new MemorySearchRequest(null, "miss", 100, null, null))).isEmpty();
        assertThat(server.searchRequests()).isOne();
        assertThat(successes).hasValue(1);
        assertThat(effective).hasValue(50);
        assertThat(server.lastSearchBody()).containsEntry("top_k", effective.get());
    }

    private static MemorySearchRequest searchFor(String query) {
        return new MemorySearchRequest(null, query, 3, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
    }

    private static final class LocalJiuwen2Server {
        private volatile boolean shouldFailFirst;
        private volatile boolean isEmptySearch;
        private volatile CountDownLatch searchGate;

        private final AtomicReference<Headers> lastSearchHeaders = new AtomicReference<>();

        private final Map<String, String> parentsByQuery = new ConcurrentHashMap<>();
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

        @SuppressWarnings("unchecked")
        private Map<String, Object> lastAddSystemMetadata() {
            Object metadata = lastAddBody.get().get("system_metadata");
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
                lastSearchHeaders.set(exchange.getRequestHeaders());
                String parent = exchange.getRequestHeaders().getFirst("traceparent");
                if (parent != null) {
                    parentsByQuery.put(String.valueOf(body.get("query")), parent);
                }
                if (searchRequests.incrementAndGet() == 1 && shouldFailFirst) {
                    writeJson(exchange, 503, Map.of("status", "unavailable"));
                    return;
                }
                if (searchGate != null) {
                    await(searchGate);
                }
                lastSearchBody.set(body);
                writeJson(exchange, 200, Map.of("items", isEmptySearch ? List.of() : List.of(searchItem()),
                    "trajectory", List.of(), "errors", List.of()));
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
