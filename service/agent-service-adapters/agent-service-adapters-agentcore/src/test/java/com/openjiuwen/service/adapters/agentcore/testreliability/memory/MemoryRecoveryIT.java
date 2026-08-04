/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.testreliability.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.agentcore.memory.mem0.GovernedMem0Api;
import com.openjiuwen.service.adapters.agentcore.memory.mem0.Mem0MemoryStore;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TC_M_002 — mem0 从故障恢复后记忆功能自动恢复验证。
 *
 * <p>测试场景：mock mem0 初始返回 HTTP 500（故障状态）→ prefetch/syncTurn 降级正常 →
 * mock mem0 切换为正常返回 → prefetch/syncTurn 自动恢复，无需重启或手动干预。</p>
 *
 * <p>降级模型说明：{@link MemoryStoreMemoryProvider} 自身不捕获 {@link ExternalSvcAdapterException}，
 * 异常由上层 handler（如 Demo {@code MemoryLifecycleAgentHandler}）捕获并降级。
 * 本测试直接使用 {@code MemoryStoreMemoryProvider}，因此模拟 handler 层的降级行为：
 * prefetch 失败时返回空字符串，syncTurn 失败时跳过（不阻断主流程）。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>mem0故障(500) → 降级正常 → mem0恢复(200) → 自动恢复记忆功能</td></tr>
 *   <tr><td>前置</td><td>长期记忆服务已启用(memory.enabled=true)，mock mem0初始返回HTTP 500</td></tr>
 *   <tr><td>步骤</td><td>3次故障Query(降级) → mock恢复 → 3次prefetch恢复验证 → 3次syncTurn恢复验证 → 无需重启验证</td></tr>
 *   <tr><td>预期</td><td>前3个Query无记忆上下文但正常返回；后6个Query有完整记忆功能；无需重启即可恢复</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class MemoryRecoveryIT {
    /** 测试用户 ID。 */
    private static final String USER_ID = "recovery-test-user";

    /** 种子记忆内容，用于验证恢复后 prefetch 是否返回正确的记忆数据。 */
    private static final String SEED_MEMORY = "用户喜欢拿铁咖啡";

    /** 测试 API Key。 */
    private static final String API_KEY = "test-recovery-key";

    /** prefetch/syncTurn 调用参数。 */
    private static final Map<String, Object> KWARGS = Map.of("user_id", USER_ID);

    private RecoverableMem0Server mem0Server;

    private Mem0MemoryStore memoryStore;

    private MemoryStoreMemoryProvider memoryProvider;

    @BeforeEach
    void setUp() throws IOException {
        // 启动 mock mem0 server，初始状态为故障（返回 500）
        mem0Server = RecoverableMem0Server.startFailing();

        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setEnabled(true);
        memory.setEndpoint(mem0Server.endpoint());
        memory.setEncryptedApiKey(API_KEY);
        memory.setRerank(true);
        memory.setTimeoutMs(3000);
        memory.getRetry().setMax(0);
        memory.getCircuitBreaker().setEnabled(false);

        GovernedMem0Api api = new GovernedMem0Api(mem0Server.endpoint(), memory);
        memoryStore = new Mem0MemoryStore(API_KEY, memory, api);
        memoryProvider = new MemoryStoreMemoryProvider(memoryStore, memory);
        memoryProvider.initialize(KWARGS);
    }

    @AfterEach
    void tearDown() {
        if (mem0Server != null) {
            mem0Server.stop();
        }
    }

    // ── TC_M_002 主路径：故障降级 → mock 恢复 → 自动恢复 ──

    /**
     * mem0 故障期间降级正常 → mock 恢复 → prefetch/syncTurn 自动恢复 → 无需重启。
     */
    @Test
    void mem0FailureThenRecoveryRestoresMemoryFunction() {
        // ── 步骤 1：发送 3 个 Query，mock mem0 返回 500，验证降级正常 ──
        for (int i = 1; i <= 3; i++) {
            String prefetchResult = degradedPrefetch("故障期间查询_" + i);
            assertThat(prefetchResult)
                .as("故障期间 prefetch 降级为空，不阻断主 Query（第 %d 次）", i)
                .isEmpty();

            degradedSyncTurn("故障用户消息_" + i, "故障助手回复_" + i);
        }

        assertThat(mem0Server.searchRequests())
            .as("故障期间 mem0 应收到 3 个 search 请求")
            .isEqualTo(3);

        assertThat(mem0Server.addRequests())
            .as("故障期间 mem0 应收到 3 个 add 请求")
            .isGreaterThanOrEqualTo(3);

        // ── 步骤 2：修改 mock mem0 为正常返回（200 + 记忆数据） ──
        mem0Server.recover();

        // ── 步骤 3：发送 3 个 Query，验证 prefetch 成功注入记忆上下文 ──
        for (int i = 1; i <= 3; i++) {
            String prefetchResult = memoryProvider.prefetch("恢复后查询_" + i, KWARGS);
            assertThat(prefetchResult)
                .as("mem0 恢复后 prefetch 应成功注入记忆上下文（第 %d 次）", i)
                .isNotEmpty()
                .contains("## Long-term Memory")
                .contains(SEED_MEMORY);

            assertThat(mem0Server.lastSearchFilters())
                .containsEntry("user_id", USER_ID);
        }

        assertThat(mem0Server.searchRequests())
            .as("恢复后应额外收到 3 个 search 请求（总计 6 个）")
            .isEqualTo(6);

        // ── 步骤 4：发送 3 个 Query，验证 syncTurn 成功写入 mem0 add ──
        for (int i = 1; i <= 3; i++) {
            final int idx = i;
            memoryProvider.syncTurn("恢复后用户消息_" + idx, "恢复后助手回复_" + idx, KWARGS);
        }

        assertThat(mem0Server.addRequests())
            .as("恢复后应额外收到 3 个 add 请求（故障期 3 + 恢复期 3 = 6 个）")
            .isEqualTo(6);

        assertThat(mem0Server.lastAddBody())
            .as("恢复后 syncTurn add body 应包含请求级 user_id")
            .containsEntry("user_id", USER_ID);

        // ── 步骤 5：验证恢复过程无需重启或手动干预 ──
        assertThat(memoryProvider.isAvailable())
            .as("恢复后 provider 应仍然可用，无需重新初始化")
            .isTrue();

        assertThat(memoryProvider.isInitialized())
            .as("恢复后 provider 应仍然已初始化，无需重新调用 initialize")
            .isTrue();

        String finalPrefetch = memoryProvider.prefetch("最终验证查询", KWARGS);
        assertThat(finalPrefetch)
            .as("恢复后功能持续正常，prefetch 返回记忆内容")
            .contains(SEED_MEMORY);
    }

    // ── TC_M_002 补充：故障期间 provider 层抛出 ExternalSvcAdapterException ──

    /**
     * 故障期间 provider 层抛出 ExternalSvcAdapterException（不吞异常），
     * handler 层捕获后降级。恢复后不再抛出异常。
     */
    @Test
    void mem0FailureCausesProviderToThrowExternalSvcAdapterException() {
        assertThatThrownBy(() -> memoryProvider.prefetch("故障测试", KWARGS))
            .as("故障期间 MemoryStoreMemoryProvider.prefetch 应抛出 ExternalSvcAdapterException")
            .isInstanceOf(ExternalSvcAdapterException.class);

        assertThatThrownBy(() -> memoryProvider.syncTurn("故障消息", "故障回复", KWARGS))
            .as("故障期间 MemoryStoreMemoryProvider.syncTurn 应抛出 ExternalSvcAdapterException")
            .isInstanceOf(ExternalSvcAdapterException.class);

        // 模拟 handler 层降级：prefetch 降级返回空字符串
        String degradedResult = degradedPrefetch("降级测试");
        assertThat(degradedResult)
            .as("handler 层捕获异常后，prefetch 降级为空字符串")
            .isEmpty();

        // ── 步骤 2：mock mem0 恢复 ──
        mem0Server.recover();

        String prefetchResult = memoryProvider.prefetch("恢复测试", KWARGS);
        assertThat(prefetchResult)
            .as("mem0 恢复后 prefetch 不再抛出异常，返回记忆上下文")
            .contains(SEED_MEMORY);

        memoryProvider.syncTurn("恢复消息", "恢复回复", KWARGS);
        assertThat(mem0Server.lastAddBody())
            .containsEntry("user_id", USER_ID);
    }

    // ── 降级辅助方法（模拟 handler 层异常捕获） ──

    /**
     * 模拟 handler 层降级行为：捕获 ExternalSvcAdapterException 并返回空字符串，
     * 与 Demo {@code MemoryLifecycleAgentHandler.prefetch()} 的生产逻辑一致。
     *
     * @param query 用户查询文本
     * @return 记忆上下文字符串；mem0 不可用时返回空字符串
     */
    private String degradedPrefetch(String query) {
        try {
            String result = memoryProvider.prefetch(query, KWARGS);
            return result != null ? result.trim() : "";
        } catch (ExternalSvcAdapterException | IllegalStateException ex) {
            // 模拟 MemoryLifecycleAgentHandler.prefetch 的降级行为：返回空字符串
            return "";
        }
    }

    /**
     * 模拟 handler 层降级行为：捕获 ExternalSvcAdapterException 并静默跳过，
     * 与 Demo {@code MemoryLifecycleAgentHandler.syncTurn()} 的生产逻辑一致。
     *
     * @param userMsg 用户消息
     * @param assistantMsg 助手消息
     */
    private void degradedSyncTurn(String userMsg, String assistantMsg) {
        try {
            memoryProvider.syncTurn(userMsg, assistantMsg, KWARGS);
        } catch (ExternalSvcAdapterException | IllegalStateException ex) {
            // 模拟 MemoryLifecycleAgentHandler.syncTurn 的降级行为：静默跳过
        }
    }

    // ── 内部类：Recoverable Mem0 Mock Server ──

    /**
     * Local mock mem0 server that can be toggled between failing (500) and healthy (200) states.
     *
     * <p>Starts in a failing state by default (returns HTTP 500 for all search/add requests).
     * Calling {@link #recover()} switches to healthy mode (returns 200 with seeded memory data).
     * This enables testing the degradation → automatic recovery lifecycle without restart.</p>
     */
    static final class RecoverableMem0Server {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static final String SEED_MEMORY_ID = "mem-recovery-1";

        private final HttpServer server;

        private final AtomicBoolean failing = new AtomicBoolean(true);

        private final AtomicInteger searchRequests = new AtomicInteger(0);

        private final AtomicInteger addRequests = new AtomicInteger(0);

        private volatile Map<String, Object> lastSearchBody = Map.of();

        private volatile Map<String, Object> lastAddBody = Map.of();

        private RecoverableMem0Server(HttpServer server) {
            this.server = server;
        }

        /**
         * Starts the mock server in failing mode (returns HTTP 500).
         *
         * @return 新创建的 RecoverableMem0Server 实例
         * @throws IllegalStateException 如果服务器启动失败
         */
        static RecoverableMem0Server startFailing() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                RecoverableMem0Server mockServer = new RecoverableMem0Server(server);
                server.createContext("/", mockServer::handle);
                server.start();
                return mockServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start recoverable mem0 server", ex);
            }
        }

        /**
         * Switches the mock server from failing to healthy mode.
         * After calling this method, all subsequent requests return HTTP 200 with memory data.
         */
        void recover() {
            failing.set(false);
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int searchRequests() {
            return searchRequests.get();
        }

        int addRequests() {
            return addRequests.get();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> lastSearchFilters() {
            Object filters = lastSearchBody.get("filters");
            return filters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        Map<String, Object> lastAddBody() {
            return lastAddBody;
        }

        void stop() {
            server.stop(0);
        }

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equals(method) && "/v3/memories/search/".equals(path)) {
                searchRequests.incrementAndGet();
                Map<String, Object> body = readBody(exchange);
                lastSearchBody = body;
                if (failing.get()) {
                    writeJson(exchange, 500, Map.of("message", "mem0 search service unavailable"));
                    return;
                }
                writeJson(exchange, 200, Map.of("results", List.of(seedMemoryRecord())));
                return;
            }

            if ("POST".equals(method) && "/v3/memories/add/".equals(path)) {
                addRequests.incrementAndGet();
                Map<String, Object> body = readBody(exchange);
                lastAddBody = body;
                if (failing.get()) {
                    writeJson(exchange, 500, Map.of("message", "mem0 add service unavailable"));
                    return;
                }
                writeJson(exchange, 200, Map.of("results", List.of(Map.of(
                    "id", "mem-recovery-added",
                    "memory", extractMessageText(body),
                    "user_id", USER_ID))));
                return;
            }

            if ("GET".equals(method) && path.startsWith("/v1/memories/") && path.endsWith("/")) {
                if (failing.get()) {
                    writeJson(exchange, 500, Map.of("message", "mem0 get service unavailable"));
                    return;
                }
                writeJson(exchange, 200, seedMemoryRecord());
                return;
            }

            if ("DELETE".equals(method) && path.startsWith("/v1/memories/") && path.endsWith("/")) {
                if (failing.get()) {
                    writeJson(exchange, 500, Map.of("message", "mem0 delete service unavailable"));
                    return;
                }
                writeBytes(exchange, 204, new byte[0]);
                return;
            }

            writeJson(exchange, 404, Map.of("message", "mem0 route not found: " + method + " " + path));
        }

        private static Map<String, Object> seedMemoryRecord() {
            return Map.of(
                "id", SEED_MEMORY_ID,
                "memory", SEED_MEMORY,
                "metadata", Map.of("source", "prefetch"),
                "score", 0.95);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return MAPPER.readValue(input, Map.class);
            }
        }

        private static String extractMessageText(Map<String, Object> body) {
            Object messages = body.get("messages");
            if (!(messages instanceof List<?> messageList)) {
                return "";
            }
            for (Object item : messageList) {
                if (item instanceof Map<?, ?> message && message.get("content") != null) {
                    return String.valueOf(message.get("content"));
                }
            }
            return "";
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
