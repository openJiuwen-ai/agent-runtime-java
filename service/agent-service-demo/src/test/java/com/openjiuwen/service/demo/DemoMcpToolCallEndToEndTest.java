/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end test for the full MCP tool-calling pipeline of the demo agent.
 *
 * <p>It boots the demo service with the {@code mcp} profile pointed at a local mock MCP server and
 * drives one non-streaming {@code /v1/query} conversation through a deterministic in-memory model.
 * The model first emits a tool call, then produces a final answer once the tool result is observed.
 * Together the assertions cover the whole chain:</p>
 * <ol>
 *   <li>the agent's ability manager lists the MCP tool (the tool list handed to the model contains
 *       {@code demo_echo});</li>
 *   <li>the model issues a tool call against {@code demo_echo};</li>
 *   <li>the MCP server receives the {@code tools/call} invocation;</li>
 *   <li>the tool result is fed back into the conversation;</li>
 *   <li>the final assistant answer reflects the tool result.</li>
 * </ol>
 */
@SpringBootTest(classes = DemoAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("mcp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoMcpToolCallEndToEndTest {
    private static final String TEST_PROVIDER = "DemoMcpToolCallProvider";
    private static final String MCP_TOOL_NAME = "demo_echo";
    private static final String CONVERSATION_ID = "mcp-tool-c1";

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);
    private static final AtomicInteger MODEL_TOOL_CALLS_EMITTED = new AtomicInteger(0);
    private static final List<String> TOOL_LISTS_SEEN_BY_MODEL = new CopyOnWriteArrayList<>();

    private static final LocalMcpServer MCP_SERVER = LocalMcpServer.start();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.demo.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.demo.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.demo.llm.api-base", () -> "mirror://demo-mcp-tool-call");
        registry.add("openjiuwen.demo.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.demo.llm.auto-discover", () -> "false");

        registry.add("DEMO_MCP_SERVER_ID", () -> "demo-mcp-e2e");
        registry.add("DEMO_MCP_SERVER_NAME", () -> "demo-mcp-e2e-tools");
        registry.add("DEMO_MCP_SERVER_PATH", MCP_SERVER::endpoint);
        registry.add("DEMO_MCP_RETRY_MAX", () -> "0");
        registry.add("DEMO_MCP_CIRCUIT_BREAKER_ENABLED", () -> "false");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new ToolCallingModelFactory());
        }
    }

    @AfterAll
    void cleanup() {
        Runner.release(CONVERSATION_ID);
        agentHandler.stop();
        MCP_SERVER.stop();
        MODEL_TOOL_CALLS_EMITTED.set(0);
        TOOL_LISTS_SEEN_BY_MODEL.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpToolCallFlowsThroughTheWholePipeline() throws Exception {
        ResponseEntity<String> resp = postQuery(Map.of(
                "message", "请帮我 echo 一下 hello",
                "conversation_id", CONVERSATION_ID,
                "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");

        // (1) The agent's ability manager exposed the MCP tool to the model.
        assertThat(TOOL_LISTS_SEEN_BY_MODEL)
                .as("model should receive the MCP tool in its tool list")
                .isNotEmpty();
        assertThat(TOOL_LISTS_SEEN_BY_MODEL.get(0))
                .as("first tool list handed to the model must contain the MCP tool")
                .contains(MCP_TOOL_NAME);

        // (2) The model issued a tool call.
        assertThat(MODEL_TOOL_CALLS_EMITTED.get())
                .as("model should have emitted at least one tool call")
                .isGreaterThanOrEqualTo(1);

        // (3) The MCP server received the tools/call invocation with the forwarded arguments.
        assertThat(MCP_SERVER.toolCallCount())
                .as("MCP server should have been invoked")
                .isGreaterThanOrEqualTo(1);
        assertThat(MCP_SERVER.lastToolCallText())
                .as("MCP server should receive the tool arguments")
                .isEqualTo("hello");

        // (4) + (5) The tool result returned through the loop and shaped the final answer.
        assertThat(result.get("content")).asString().contains("demo_echo:hello");
    }

    private ResponseEntity<String> postQuery(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(body, headers), String.class);
    }

    private static final class ToolCallingModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new ToolCallingModelClient(modelConfig, clientConfig);
        }
    }

    /**
     * Deterministic model: emits a {@code demo_echo} tool call on the first turn, then a final answer
     * that embeds the observed tool result on the next turn.
     */
    private static final class ToolCallingModelClient extends BaseModelClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private ToolCallingModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                                       Integer maxTokens, String stop, BaseOutputParser outputParser,
                                       Float timeout, Map<String, Object> kwargs) {
            TOOL_LISTS_SEEN_BY_MODEL.add(serializeTools(tools));

            List<Map<String, Object>> converted = new ArrayList<>(convertMessagesToDict(messages));
            String observedToolResult = converted.stream()
                    .filter(message -> "tool".equals(String.valueOf(message.get("role"))))
                    .map(message -> String.valueOf(message.get("content")))
                    .reduce((first, second) -> second)
                    .orElse(null);

            if (observedToolResult != null) {
                return new AssistantMessage("已调用工具，工具返回: " + observedToolResult);
            }

            MODEL_TOOL_CALLS_EMITTED.incrementAndGet();
            ToolCall toolCall = ToolCall.builder()
                    .id("call-1")
                    .type("function")
                    .name(MCP_TOOL_NAME)
                    .arguments("{\"text\":\"hello\"}")
                    .index(0)
                    .build();
            return AssistantMessage.builder()
                    .role("assistant")
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
                                                      String model, Integer maxTokens, String stop,
                                                      BaseOutputParser outputParser, Float timeout,
                                                      Map<String, Object> kwargs) {
            return List.<AssistantMessageChunk>of().iterator();
        }

        private static String serializeTools(Object tools) {
            if (tools == null) {
                return "";
            }
            try {
                return MAPPER.writeValueAsString(tools);
            } catch (Exception ex) {
                return String.valueOf(tools);
            }
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
                                                     String negativePrompt, int n, boolean promptExtend,
                                                     boolean watermark, int seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> messages, String model, String voice,
                                                      String languageType, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> messages, String imgUrl, String audioUrl,
                                                     String model, String size, String resolution, int duration,
                                                     boolean promptExtend, boolean watermark, String negativePrompt,
                                                     Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Minimal JSON-RPC MCP server exposing a single {@code demo_echo} tool and recording invocations.
     */
    private static final class LocalMcpServer {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
        };

        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        private final AtomicReference<String> lastToolCallText = new AtomicReference<>();

        private LocalMcpServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static LocalMcpServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = newServerExecutor();
                LocalMcpServer localServer = new LocalMcpServer(server, executor);
                server.createContext("/mcp", localServer::handle);
                server.setExecutor(executor);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start demo MCP server", ex);
            }
        }

        private static ThreadPoolExecutor newServerExecutor() {
            return new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(100),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private int toolCallCount() {
            return toolCallCount.get();
        }

        private String lastToolCallText() {
            return lastToolCallText.get();
        }

        private void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
            Object method = request.get("method");
            if ("initialize".equals(method)) {
                writeJson(exchange, response(request.get("id"), Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "serverInfo", Map.of("name", "demo-mcp-server", "version", "1.0.0"))));
                return;
            }
            if ("tools/list".equals(method)) {
                writeJson(exchange, response(request.get("id"), Map.of(
                        "tools", List.of(Map.of(
                                "name", MCP_TOOL_NAME,
                                "description", "Echo from demo MCP server",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of("text", Map.of("type", "string"))))))));
                return;
            }
            if ("tools/call".equals(method)) {
                Map<String, Object> params = asMap(request.get("params"));
                Map<String, Object> arguments = asMap(params.get("arguments"));
                String text = String.valueOf(arguments.getOrDefault("text", ""));
                toolCallCount.incrementAndGet();
                lastToolCallText.set(text);
                writeJson(exchange, response(request.get("id"), Map.of(
                        "content", List.of(Map.of("type", "text", "text", "demo_echo:" + text)))));
                return;
            }
            writeJson(exchange, Map.of(
                    "jsonrpc", "2.0",
                    "id", request.get("id"),
                    "error", Map.of("code", -32601, "message", "Method not found")));
        }

        private Map<String, Object> response(Object id, Map<String, Object> result) {
            return Map.of("jsonrpc", "2.0", "id", id, "result", result);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> asMap(Object value) {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
