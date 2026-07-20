/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.sandbox;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * End-to-end smoke test for agent-driven sandbox tool calls.
 *
 * @since 0.1.0
 */
@Tag("smoke")
@SpringBootTest(classes = SandboxDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxAgentToolCallEndToEndTest {
    private static final String TEST_PROVIDER = "SandboxAgentToolCallProvider";
    private static final String CONVERSATION_ID = "sandbox-agent-c1";
    private static final String READ_FILE_TOOL_NAME = "readFile";
    private static final String SANDBOX_PATH = "/tmp/demo.txt";

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);
    private static final AtomicInteger MODEL_TOOL_CALLS_EMITTED = new AtomicInteger(0);
    private static final List<String> TOOL_LISTS_SEEN_BY_MODEL = new CopyOnWriteArrayList<>();

    private static final LocalJiuwenBoxServer SANDBOX_SERVER = LocalJiuwenBoxServer.start();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.service.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.service.llm.api-base", () -> "mirror://sandbox-agent-tool-call");
        registry.add("openjiuwen.service.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.service.llm.auto-discover", () -> "false");

        registry.add("openjiuwen.service.external.sandbox.enabled", () -> "true");
        registry.add("openjiuwen.service.external.sandbox.timeout-ms", () -> "3000");
        registry.add("openjiuwen.service.external.sandbox.retry.max", () -> "0");
        registry.add("openjiuwen.service.external.sandbox.circuit-breaker.enabled", () -> "false");
        registry.add("openjiuwen.service.external.sandbox.servers[0].server-id", () -> "sandbox-agent-e2e");
        registry.add("openjiuwen.service.external.sandbox.servers[0].service-url", SANDBOX_SERVER::endpoint);
        registry.add("openjiuwen.service.external.sandbox.servers[0].sandbox-type", () -> "jiuwenbox");
        registry.add("openjiuwen.service.external.sandbox.servers[0].launcher-type", () -> "pre_deploy");
        registry.add("openjiuwen.service.external.sandbox.servers[0].root-path", () -> ".");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new SandboxToolCallingModelFactory());
        }
    }

    @AfterAll
    void cleanup() {
        Runner.release(CONVERSATION_ID);
        agentHandler.stop();
        SANDBOX_SERVER.stop();
        MODEL_TOOL_CALLS_EMITTED.set(0);
        TOOL_LISTS_SEEN_BY_MODEL.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentToolCallReadsFileThroughDecoratedSandboxClient() throws IOException {
        ResponseEntity<String> response = postQuery(Map.of(
                "message", "请读取沙箱里的 /tmp/demo.txt",
                "conversation_id", CONVERSATION_ID,
                "stream", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");

        assertThat(TOOL_LISTS_SEEN_BY_MODEL)
                .as("model should receive sandbox readFile in its tool list")
                .isNotEmpty();
        assertThat(TOOL_LISTS_SEEN_BY_MODEL.get(0))
                .as("first tool list handed to the model must contain readFile")
                .contains(READ_FILE_TOOL_NAME);
        assertThat(MODEL_TOOL_CALLS_EMITTED.get())
                .as("model should have emitted at least one sandbox tool call")
                .isGreaterThanOrEqualTo(1);

        assertThat(SANDBOX_SERVER.requests()).anySatisfy(request -> assertThat(request.path())
                .isEqualTo("/api/v1/sandboxes"));
        assertThat(SANDBOX_SERVER.requests()).anySatisfy(request -> {
            assertThat(request.path()).isEqualTo("/api/v1/sandboxes/mock-sandbox/download");
            assertThat(request.query()).containsEntry("sandbox_path", SANDBOX_PATH);
        });
        assertThat(result.get("content")).asString().contains("mock jiuwenbox file:" + SANDBOX_PATH);
    }

    private ResponseEntity<String> postQuery(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(body, headers), String.class);
    }

    private static final class SandboxToolCallingModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new SandboxToolCallingModelClient(modelConfig, clientConfig);
        }
    }

    private static final class SandboxToolCallingModelClient extends BaseModelClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private SandboxToolCallingModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            TOOL_LISTS_SEEN_BY_MODEL.add(serializeTools(tools));
            List<Map<String, Object>> converted = new ArrayList<>(convertMessagesToDict(messages));
            String observedToolResult = converted.stream()
                    .filter(message -> "tool".equals(String.valueOf(message.get("role"))))
                    .map(message -> String.valueOf(message.get("content")))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (observedToolResult != null) {
                return new AssistantMessage("沙箱读取结果: " + observedToolResult);
            }

            MODEL_TOOL_CALLS_EMITTED.incrementAndGet();
            ToolCall toolCall = ToolCall.builder()
                    .id("sandbox-call-1")
                    .type("function")
                    .name(READ_FILE_TOOL_NAME)
                    .arguments(readFileArguments())
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
                String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            return List.<AssistantMessageChunk>of().iterator();
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

        private static String readFileArguments() {
            try {
                return MAPPER.writeValueAsString(Map.of(
                        "path", SANDBOX_PATH,
                        "mode", "text",
                        "encoding", "UTF-8",
                        "chunkSize", 0,
                        "options", Map.of()));
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to serialize sandbox readFile arguments", ex);
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

    private static final class LocalJiuwenBoxServer {
        private static final Logger LOGGER = LoggerFactory.getLogger(LocalJiuwenBoxServer.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
        };
        private static final String SANDBOX_ID = "mock-sandbox";

        private final HttpServer server;
        private final ExecutorService executor;
        private final ConcurrentLinkedQueue<RecordedRequest> requests = new ConcurrentLinkedQueue<>();

        private LocalJiuwenBoxServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static LocalJiuwenBoxServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = newServerExecutor();
                LocalJiuwenBoxServer localServer = new LocalJiuwenBoxServer(server, executor);
                server.createContext("/api/v1/sandboxes", localServer::handleSandboxes);
                server.setExecutor(executor);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start local JiuwenBox server", ex);
            }
        }

        private static ThreadPoolExecutor newServerExecutor() {
            return new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(100),
                    new NamedThreadFactory(),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<RecordedRequest> requests() {
            return new ArrayList<>(requests);
        }

        private void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handleSandboxes(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            Map<String, String> query = queryParameters(uri);
            Map<String, Object> body = readJsonBody(exchange);
            requests.add(new RecordedRequest(method, path, query, body));

            if ("POST".equals(method) && "/api/v1/sandboxes".equals(path)) {
                writeJson(exchange, 200, Map.of("id", SANDBOX_ID));
                return;
            }
            if ("GET".equals(method) && sandboxPath("/download").equals(path)) {
                writeBytes(exchange, 200, ("mock jiuwenbox file:" + query.get("sandbox_path"))
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("DELETE".equals(method) && sandboxPath("").equals(path)) {
                writeBytes(exchange, 204, new byte[0]);
                return;
            }
            writeJson(exchange, 404, Map.of("message", "sandbox route not found"));
        }

        private String sandboxPath(String suffix) {
            return "/api/v1/sandboxes/" + SANDBOX_ID + suffix;
        }

        private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            return MAPPER.readValue(bytes, MAP_TYPE);
        }

        private Map<String, String> queryParameters(URI uri) {
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isBlank()) {
                return Map.of();
            }
            return List.of(rawQuery.split("&")).stream()
                    .map(part -> part.split("=", 2))
                    .collect(Collectors.toMap(
                            part -> decode(part[0]),
                            part -> part.length > 1 ? decode(part[1]) : ""));
        }

        private String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            writeBytes(exchange, status, body);
        }

        private void writeBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private static final class NamedThreadFactory implements ThreadFactory {
            private final AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "sandbox-e2e-server-" + threadIndex.incrementAndGet());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((failedThread, ex) ->
                        LOGGER.error("Uncaught exception in {}", failedThread.getName(), ex));
                return thread;
            }
        }
    }

    private record RecordedRequest(
            String method,
            String path,
            Map<String, String> query,
            Map<String, Object> body) {
    }
}
