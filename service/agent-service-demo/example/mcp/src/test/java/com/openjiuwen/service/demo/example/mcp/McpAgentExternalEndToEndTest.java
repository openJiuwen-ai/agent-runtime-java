/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.mcp;

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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full Agent-to-MCP E2E test against a configured external JSON MCP server.
 *
 * @since 0.1.0
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "demo.mcp.e2e.server-path", matches = ".+")
@SpringBootTest(classes = McpDemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class McpAgentExternalEndToEndTest {
    private static final String SERVER_PATH_PROPERTY = "demo.mcp.e2e.server-path";

    private static final String TEST_PROVIDER = "McpAgentExternalProvider";

    private static final String TOOL_NAME_PROPERTY = "demo.mcp.e2e.tool-name";

    private static final String TOOL_ARGUMENTS_PROPERTY = "demo.mcp.e2e.tool-arguments";

    private static final String EXPECTED_CONTENT_PROPERTY = "demo.mcp.e2e.expected-content";

    private static final String USER_MESSAGE_PROPERTY = "demo.mcp.e2e.user-message";

    private static final String CONVERSATION_ID_PROPERTY = "demo.mcp.e2e.conversation-id";

    private static final String SERVER_ID_PROPERTY = "demo.mcp.e2e.server-id";

    private static final String SERVER_NAME_PROPERTY = "demo.mcp.e2e.server-name";

    private static final String DEFAULT_TOOL_NAME = "demo_echo";

    private static final String DEFAULT_TOOL_ARGUMENTS = "{\"text\":\"hello\"}";

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean();

    private static final AtomicInteger MODEL_TOOL_CALLS = new AtomicInteger();

    private static final List<String> MODEL_TOOL_LISTS = new CopyOnWriteArrayList<>();

    private static final AtomicReference<String> MODEL_TOOL_NAME = new AtomicReference<>();

    private static final AtomicReference<String> MODEL_TOOL_ARGUMENTS = new AtomicReference<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.service.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.service.llm.api-base", () -> "mirror://mcp-agent-external");
        registry.add("openjiuwen.service.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.service.llm.auto-discover", () -> "false");
        registry.add("openjiuwen.service.external.mcp.retry.max", () -> "0");
        registry.add("openjiuwen.service.external.mcp.circuit-breaker.enabled", () -> "false");
        registry.add("openjiuwen.service.external.mcp.servers[0].server-id",
            McpAgentExternalEndToEndTest::serverId);
        registry.add("openjiuwen.service.external.mcp.servers[0].server-name",
            McpAgentExternalEndToEndTest::serverName);
        registry.add("openjiuwen.service.external.mcp.servers[0].server-path",
            () -> System.getProperty(SERVER_PATH_PROPERTY));
        registry.add("openjiuwen.service.external.mcp.servers[0].client-type", () -> "streamable-http");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new ToolCallingModelFactory());
        }
    }

    @AfterAll
    void cleanup() {
        Runner.release(conversationId());
        agentHandler.stop();
        MODEL_TOOL_CALLS.set(0);
        MODEL_TOOL_LISTS.clear();
        MODEL_TOOL_NAME.set(null);
        MODEL_TOOL_ARGUMENTS.set(null);
    }

    @Test
    void userQueryCallsConfiguredExternalMcpToolAndReturnsResult(CapturedOutput output) throws Exception {
        ResponseEntity<String> response = postQuery(Map.of("message", userMessage(),
            "conversation_id", conversationId(), "stream", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = MAPPER.readValue(response.getBody(), new TypeReference<>() {
        });
        assertThat(body.get("result")).isInstanceOfSatisfying(Map.class,
            result -> assertThat(result.get("content")).asString().contains(expectedContent()));
        assertThat(MODEL_TOOL_LISTS).isNotEmpty();
        assertThat(MODEL_TOOL_LISTS.get(0)).contains(toolName());
        assertThat(MODEL_TOOL_CALLS.get()).isGreaterThanOrEqualTo(1);
        assertThat(MODEL_TOOL_NAME.get()).isEqualTo(toolName());
        assertThat(MODEL_TOOL_ARGUMENTS.get()).isEqualTo(toolArguments());

        assertThat(output).contains("EXTERNAL_CALL_AUDIT")
            .contains("adapter=MCP")
            .contains("success=true")
            .contains("method=mcp.tools/call");
    }

    private ResponseEntity<String> postQuery(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(body, headers), String.class);
    }

    private static String serverId() {
        return property(SERVER_ID_PROPERTY, "mcp-external-e2e");
    }

    private static String serverName() {
        return property(SERVER_NAME_PROPERTY, "mcp-external-tools");
    }

    private static String toolName() {
        return property(TOOL_NAME_PROPERTY, DEFAULT_TOOL_NAME);
    }

    private static String toolArguments() {
        return property(TOOL_ARGUMENTS_PROPERTY, DEFAULT_TOOL_ARGUMENTS);
    }

    private static String expectedContent() {
        return property(EXPECTED_CONTENT_PROPERTY, "demo_echo:hello");
    }

    private static String conversationId() {
        return property(CONVERSATION_ID_PROPERTY, "mcp-external-c1");
    }

    private static String userMessage() {
        return property(USER_MESSAGE_PROPERTY, "请调用 " + toolName() + " 工具");
    }

    private static String property(String name, String defaultValue) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? defaultValue : value;
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

    private static final class ToolCallingModelClient extends BaseModelClient {
        private ToolCallingModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
            Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs) {
            MODEL_TOOL_LISTS.add(serializeTools(tools));
            List<Map<String, Object>> converted = new ArrayList<>(convertMessagesToDict(messages));
            String toolResult = converted.stream()
                .filter(message -> "tool".equals(String.valueOf(message.get("role"))))
                .map(message -> String.valueOf(message.get("content")))
                .reduce((first, second) -> second)
                .orElse(null);
            if (toolResult != null) {
                return new AssistantMessage("MCP 工具返回: " + toolResult);
            }

            MODEL_TOOL_CALLS.incrementAndGet();
            MODEL_TOOL_NAME.set(toolName());
            MODEL_TOOL_ARGUMENTS.set(toolArguments());
            ToolCall toolCall = ToolCall.builder()
                .id("mcp-call-1")
                .type("function")
                .name(toolName())
                .arguments(toolArguments())
                .index(0)
                .build();
            return AssistantMessage.builder().role("assistant").content("").toolCalls(List.of(toolCall)).build();
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
                throw new IllegalStateException("Failed to serialize MCP tools", ex);
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
}
