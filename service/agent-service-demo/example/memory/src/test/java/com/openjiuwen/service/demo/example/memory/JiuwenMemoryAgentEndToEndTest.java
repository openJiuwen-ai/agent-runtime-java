/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end test for Jiuwen Memory Engine provider.
 * Uses a local mock server simulating Jiuwen API paths.
 *
 * <p>Note: Jiuwen does not support delete-by-memory-id, so delete is not tested.
 *
 * @since 0.1.0
 */
@Tag("smoke")
@SpringBootTest(classes = MemoryDemoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JiuwenMemoryAgentEndToEndTest {
    private static final String TEST_PROVIDER = "JiuwenMemoryE2eProvider";

    private static final String USER_ID = "jiuwen-e2e-user";

    private static final String MEMORY_ID = "mem-1";

    private static final String SEARCH_TOOL_NAME = "memory_search";

    private static final String ADD_TOOL_NAME = "memory_add";

    private static final String GET_TOOL_NAME = "memory_get";

    private static final String SEARCH_QUERY = "咖啡偏好";

    private static final String SEED_MEMORY = "用户喜欢拿铁咖啡";

    private static final String STORED_CONTENT = "用户喜欢手冲咖啡";

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);

    private static final List<String> TOOL_LISTS_SEEN_BY_MODEL = new CopyOnWriteArrayList<>();

    private static final List<String> USER_MESSAGES_SEEN_BY_MODEL = new CopyOnWriteArrayList<>();

    private static final LocalJiuwenServer JIUWEN_SERVER = LocalJiuwenServer.start();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    @Autowired
    private MemoryStore memoryStore;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.service.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.service.llm.api-base", () -> "mirror://jiuwen-memory-e2e");
        registry.add("openjiuwen.service.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.service.llm.auto-discover", () -> "false");

        registry.add("openjiuwen.service.middleware.memory.enabled", () -> "true");
        registry.add("openjiuwen.service.middleware.memory.provider", () -> "jiuwen");
        registry.add("openjiuwen.service.middleware.memory.endpoint", JIUWEN_SERVER::endpoint);
        registry.add("openjiuwen.service.middleware.memory.encrypted-api-key", () -> "jiuwen-test-key");
        registry.add("openjiuwen.service.middleware.memory.request-scoped-session", () -> "true");
        registry.add("openjiuwen.service.middleware.memory.timeout-ms", () -> "3000");
        registry.add("openjiuwen.service.middleware.memory.retry.max", () -> "0");
        registry.add("openjiuwen.service.middleware.memory.circuit-breaker.enabled", () -> "false");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new JiuwenE2eModelFactory());
        }
    }

    @BeforeEach
    void reset() {
        JIUWEN_SERVER.reset();
        TOOL_LISTS_SEEN_BY_MODEL.clear();
        USER_MESSAGES_SEEN_BY_MODEL.clear();
        Runner.release("jiuwen-e2e-lifecycle");
        Runner.release("jiuwen-e2e-search");
        Runner.release("jiuwen-e2e-get");
        Runner.release("jiuwen-e2e-add");
    }

    @AfterAll
    void cleanup() {
        agentHandler.stop();
        JIUWEN_SERVER.stop();
        TOOL_LISTS_SEEN_BY_MODEL.clear();
        USER_MESSAGES_SEEN_BY_MODEL.clear();
    }

    @Test
    void lifecyclePrefetchSyncTurnAndMemoryToolsReachMockJiuwen() throws IOException {
        assertThat(memoryStore.getProvider()).isEqualTo("jiuwen");

        verifyLifecyclePrefetchAndSyncTurn();
        verifyMemorySearchTool();
        verifyMemoryGetTool();
        verifyMemoryAddTool();
    }

    private void verifyLifecyclePrefetchAndSyncTurn() throws IOException {
        ResponseEntity<String> lifecycleResponse = postQuery("jiuwen-e2e-lifecycle",
            "请直接回答：我喜欢喝什么咖啡？");

        verifyLifecyclePrefetch(lifecycleResponse);
        verifyLifecycleSyncTurn();
    }

    private void verifyLifecyclePrefetch(ResponseEntity<String> lifecycleResponse) throws IOException {
        assertThat(lifecycleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultMap(lifecycleResponse).get("content")).asString().contains("拿铁咖啡");
        assertThat(JIUWEN_SERVER.searchRequests())
            .as("request lifecycle should prefetch through MemoryProvider -> MemoryStore.search")
            .isEqualTo(1);
        assertThat(JIUWEN_SERVER.searchBodies().get(0))
            .containsEntry("query", "请直接回答：我喜欢喝什么咖啡？");
        assertThat(JIUWEN_SERVER.searchBodies().get(0))
            .containsEntry("user_id", USER_ID);
        assertThat(USER_MESSAGES_SEEN_BY_MODEL)
            .anySatisfy(message -> assertThat(message)
                .contains("<memory-context>")
                .contains(SEED_MEMORY)
                .contains("<user-message>")
                .contains("请直接回答：我喜欢喝什么咖啡？"));
    }

    private void verifyLifecycleSyncTurn() {
        assertThat(JIUWEN_SERVER.addBodies())
            .as("request lifecycle should sync user/assistant turn through MemoryProvider -> MemoryStore.add")
            .anySatisfy(body -> {
                assertThat(body).containsEntry("user_id", USER_ID);
                assertThat(messages(body)).anySatisfy(message -> assertThat(message)
                    .containsEntry("role", "user")
                    .containsEntry("content", "请直接回答：我喜欢喝什么咖啡？"));
                assertThat(messages(body)).anySatisfy(message -> assertThat(message)
                    .containsEntry("role", "assistant")
                    .containsEntry("content", "根据长期记忆，用户喜欢拿铁咖啡。"));
            });
    }

    private void verifyMemorySearchTool() throws IOException {
        ResponseEntity<String> searchResponse = postQuery("jiuwen-e2e-search", "请用 memory_search 查找咖啡偏好");
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultContent(searchResponse)).contains("拿铁咖啡");
        assertThat(TOOL_LISTS_SEEN_BY_MODEL).anyMatch(tools -> tools.contains(SEARCH_TOOL_NAME));
        assertThat(JIUWEN_SERVER.searchBodies())
            .as("prefetch search plus memory_search tool call should both reach jiuwen")
            .anySatisfy(body -> assertThat(body).containsEntry("query", SEARCH_QUERY));
    }

    private void verifyMemoryGetTool() throws IOException {
        ResponseEntity<String> getResponse = postQuery("jiuwen-e2e-get",
            "请用 memory_get 查看 memory_id 为 mem-1 的记录");
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultContent(getResponse)).contains(SEED_MEMORY);
        assertThat(TOOL_LISTS_SEEN_BY_MODEL).anyMatch(tools -> tools.contains(GET_TOOL_NAME));
        assertThat(JIUWEN_SERVER.getPageRequests()).isEqualTo(1);
    }

    private void verifyMemoryAddTool() throws IOException {
        ResponseEntity<String> addResponse = postQuery("jiuwen-e2e-add", "请用 memory_add 记录我的长期偏好");
        assertThat(addResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultContent(addResponse)).contains("Fact stored");
        assertThat(TOOL_LISTS_SEEN_BY_MODEL).anyMatch(tools -> tools.contains(ADD_TOOL_NAME));
        assertThat(JIUWEN_SERVER.addBodies())
            .anySatisfy(body -> assertThat(messages(body))
                .anySatisfy(message -> assertThat(message).containsEntry("content", STORED_CONTENT)));
        assertThat(JIUWEN_SERVER.containsMemoryText(STORED_CONTENT)).isTrue();
    }

    private ResponseEntity<String> postQuery(String conversationId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(Map.of(
            "conversation_id", conversationId,
            "user_id", USER_ID,
            "message", message,
            "stream", false), headers), String.class);
    }

    private String resultContent(ResponseEntity<String> response) throws IOException {
        return String.valueOf(resultMap(response).get("content"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultMap(ResponseEntity<String> response) throws IOException {
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        return (Map<String, Object>) json.get("result");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static final class JiuwenE2eModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new JiuwenE2eModelClient(modelConfig, clientConfig);
        }
    }

    private static final class JiuwenE2eModelClient extends BaseModelClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private JiuwenE2eModelClient(ModelRequestConfig modelConfig, ModelClientConfig clientClientConfig) {
            super(modelConfig, clientClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
            Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs) {
            TOOL_LISTS_SEEN_BY_MODEL.add(serializeTools(tools));
            List<Map<String, Object>> converted = new ArrayList<>(convertMessagesToDict(messages));
            Optional<String> observedToolResult = converted.stream()
                .filter(message -> "tool".equals(String.valueOf(message.get("role"))))
                .map(message -> String.valueOf(message.get("content")))
                .reduce((first, second) -> second);
            if (observedToolResult.isPresent()) {
                return new AssistantMessage("工具结果: " + observedToolResult.get());
            }

            String lastUserMessage = converted.stream()
                .filter(message -> "user".equals(String.valueOf(message.get("role"))))
                .map(message -> String.valueOf(message.get("content")))
                .reduce((first, second) -> second)
                .orElse("");
            USER_MESSAGES_SEEN_BY_MODEL.add(lastUserMessage);

            String currentUserMessage = currentUserMessage(lastUserMessage);
            if (currentUserMessage.contains("请直接回答")) {
                return new AssistantMessage("根据长期记忆，用户喜欢拿铁咖啡。");
            }
            return AssistantMessage.builder()
                .role("assistant")
                .content("")
                .toolCalls(List.of(selectToolCall(currentUserMessage)))
                .build();
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
            String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
            Map<String, Object> kwargs) {
            return List.<AssistantMessageChunk>of().iterator();
        }

        private static ToolCall selectToolCall(String userMessage) {
            if (userMessage.contains("memory_get") || userMessage.contains("查看")) {
                return toolCall("jiuwen-get-1", GET_TOOL_NAME, Map.of("memory_id", MEMORY_ID));
            }
            if (userMessage.contains("memory_add") || userMessage.contains("记录")) {
                return toolCall("jiuwen-add-1", ADD_TOOL_NAME, Map.of("content", STORED_CONTENT));
            }
            return toolCall("jiuwen-search-1", SEARCH_TOOL_NAME, Map.of("query", SEARCH_QUERY, "top_k", 5));
        }

        private static String currentUserMessage(String message) {
            String open = "<user-message>";
            String close = "</user-message>";
            int start = message.indexOf(open);
            int end = message.indexOf(close);
            if (start >= 0 && end > start) {
                return message.substring(start + open.length(), end).trim();
            }
            return message;
        }

        private static ToolCall toolCall(String id, String name, Map<String, Object> arguments) {
            return ToolCall.builder()
                .id(id)
                .type("function")
                .name(name)
                .arguments(toolArguments(arguments))
                .index(0)
                .build();
        }

        private static String serializeTools(Object tools) {
            if (tools == null) {
                return "";
            }
            try {
                return MAPPER.writeValueAsString(tools);
            } catch (IOException ex) {
                return String.valueOf(tools);
            }
        }

        private static String toolArguments(Map<String, Object> args) {
            try {
                return MAPPER.writeValueAsString(args);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to serialize memory tool arguments", ex);
            }
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
            String negativePrompt, int n, boolean shouldPromptExtend, boolean shouldWatermark, int seed,
            Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> messages, String model, String voice,
            String languageType, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> messages, String imgUrl, String audioUrl,
            String model, String size, String resolution, int duration, boolean shouldPromptExtend,
            boolean shouldWatermark, String negativePrompt, Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Local mock server simulating Jiuwen Memory Engine API.
     *
     * <p>Paths:
     * <ul>
     *   <li>POST /search_memory/ - semantic search</li>
     *   <li>POST /add_messages/ - add messages as memory</li>
     *   <li>POST /get_user_mem_by_page/ - paginated memory retrieval</li>
     *   <li>GET /health - health check</li>
     * </ul>
     */
    private static final class LocalJiuwenServer {
        private final HttpServer server;

        private final AtomicInteger addRequests = new AtomicInteger(0);

        private final AtomicInteger searchRequests = new AtomicInteger(0);

        private final AtomicInteger getPageRequests = new AtomicInteger(0);

        private final AtomicInteger idSequence = new AtomicInteger(2);

        private final List<Map<String, Object>> addBodies = new CopyOnWriteArrayList<>();

        private final List<Map<String, Object>> searchBodies = new CopyOnWriteArrayList<>();

        private final Map<String, Map<String, Object>> memories = new java.util.concurrent.ConcurrentHashMap<>();

        private LocalJiuwenServer(HttpServer server) {
            this.server = server;
            reset();
        }

        private static LocalJiuwenServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LocalJiuwenServer localServer = new LocalJiuwenServer(server);
                server.createContext("/", localServer::handle);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start local jiuwen server", ex);
            }
        }

        private void reset() {
            addRequests.set(0);
            searchRequests.set(0);
            getPageRequests.set(0);
            idSequence.set(2);
            addBodies.clear();
            searchBodies.clear();
            memories.clear();
            seedMemory(MEMORY_ID, SEED_MEMORY);
        }

        private void seedMemory(String id, String text) {
            memories.put(id, Map.of(
                "mem_id", id,
                "content", text,
                "type", "fact"));
        }

        private boolean containsMemoryText(String text) {
            return memories.values().stream()
                .anyMatch(record -> text.equals(String.valueOf(record.get("content"))));
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int searchRequests() {
            return searchRequests.get();
        }

        private int getPageRequests() {
            return getPageRequests.get();
        }

        private List<Map<String, Object>> addBodies() {
            return List.copyOf(addBodies);
        }

        private List<Map<String, Object>> searchBodies() {
            return List.copyOf(searchBodies);
        }

        private void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("POST".equals(method) && "/search_memory/".equals(path)) {
                searchRequests.incrementAndGet();
                searchBodies.add(readBody(exchange));
                writeJson(exchange, 200, Map.of("results", searchResults()));
                return;
            }
            if ("POST".equals(method) && "/add_messages/".equals(path)) {
                addRequests.incrementAndGet();
                Map<String, Object> stored = storeAddedMemory(exchange);
                writeJson(exchange, 200, Map.of("results", stored.isEmpty() ? List.of() : List.of(stored)));
                return;
            }
            if ("POST".equals(method) && "/get_user_mem_by_page/".equals(path)) {
                getPageRequests.incrementAndGet();
                readBody(exchange); // consume body
                writeJson(exchange, 200, Map.of("results", paginatedResults()));
                return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                writeJson(exchange, 200, Map.of("status", "healthy"));
                return;
            }
            writeJson(exchange, 404, Map.of("message", "jiuwen route not found: " + method + " " + path));
        }

        private List<Map<String, Object>> searchResults() {
            return memories.values().stream()
                .map(record -> {
                    Map<String, Object> result = new LinkedHashMap<>(record);
                    result.put("score", 0.9);
                    return result;
                })
                .toList();
        }

        private List<Map<String, Object>> paginatedResults() {
            return new ArrayList<>(memories.values());
        }

        private Map<String, Object> storeAddedMemory(HttpExchange exchange) throws IOException {
            Map<String, Object> body = readBody(exchange);
            addBodies.add(body);
            String text = extractMessageText(body);
            if (text.isBlank()) {
                return Map.of();
            }
            String memoryId = "mem-" + idSequence.getAndIncrement();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("mem_id", memoryId);
            record.put("content", text);
            record.put("type", "fact");
            memories.put(memoryId, record);
            return record;
        }

        private String extractMessageText(Map<String, Object> body) {
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

        @SuppressWarnings("unchecked")
        private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return new ObjectMapper().readValue(input, Map.class);
            }
        }

        private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
            byte[] body = new ObjectMapper().writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        }
    }
}
