/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.extensions.sys_operation.sandbox.providers.jiuwenbox.SandboxLifecycleHelper;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;
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

/**
 * Full Agent-to-Sandbox E2E test against a configured external JiuwenBox service.
 *
 * @since 0.1.0
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "demo.sandbox.e2e.service-url", matches = ".+")
@SpringBootTest(classes = SandboxDemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SandboxAgentExternalEndToEndTest {
    private static final String SERVICE_URL_PROPERTY = "demo.sandbox.e2e.service-url";

    private static final String TEST_PROVIDER = "SandboxAgentExternalProvider";

    private static final String PREPARE_FILE_REQUEST = "sandbox-e2e-prepare-file";

    private static final String READ_FILE_REQUEST = "sandbox-e2e-read-file";

    private static final String EXECUTE_CODE_REQUEST = "sandbox-e2e-execute-code";

    private static final String EXECUTE_CMD = "executeCmd";

    private static final String READ_FILE = "readFile";

    private static final String EXECUTE_CODE = "executeCode";

    private static final String SANDBOX_PATH = "/tmp/openjiuwen-runtime-sandbox-e2e.txt";

    private static final String FILE_MARKER = "sandbox-file-ok";

    private static final String CODE_MARKER = "sandbox-code-ok";

    private static final List<String> CONVERSATION_IDS = List.of(
        "sandbox-external-command-c1", "sandbox-external-read-c1", "sandbox-external-code-c1");

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean();

    private static final List<String> MODEL_TOOL_LISTS = new CopyOnWriteArrayList<>();

    private static final List<ToolInvocation> MODEL_TOOL_CALLS = new CopyOnWriteArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    @Autowired
    private AgentCoreSandboxClientFactory sandboxClientFactory;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.service.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.service.llm.api-base", () -> "mirror://sandbox-agent-external");
        registry.add("openjiuwen.service.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.service.llm.auto-discover", () -> "false");
        registry.add("openjiuwen.service.external.sandbox.enabled", () -> "true");
        registry.add("openjiuwen.service.external.sandbox.timeout-ms", () -> "3000");
        registry.add("openjiuwen.service.external.sandbox.retry.max", () -> "0");
        registry.add("openjiuwen.service.external.sandbox.circuit-breaker.enabled", () -> "true");
        registry.add("openjiuwen.service.external.sandbox.circuit-breaker.failure-threshold", () -> "1");
        registry.add("openjiuwen.service.external.sandbox.circuit-breaker.reset-timeout-ms", () -> "60000");
        registry.add("openjiuwen.service.external.sandbox.audit.enabled", () -> "true");
        registry.add("openjiuwen.service.external.sandbox.servers[0].server-id", () -> "sandbox-external-e2e");
        registry.add("openjiuwen.service.external.sandbox.servers[0].service-url",
            () -> System.getProperty(SERVICE_URL_PROPERTY));
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
        CONVERSATION_IDS.forEach(Runner::release);
        try {
            agentHandler.stop();
        } finally {
            SandboxLifecycleHelper.deleteJiuwenBoxSandbox("external-e2e", 5);
            MODEL_TOOL_LISTS.clear();
            MODEL_TOOL_CALLS.clear();
        }
    }

    @Test
    void userQueriesCallThreeSandboxToolsAndExerciseGovernance(CapturedOutput output) throws IOException {
        ResponseEntity<String> commandResponse = postQuery(PREPARE_FILE_REQUEST, CONVERSATION_IDS.get(0));
        ResponseEntity<String> readResponse = postQuery(READ_FILE_REQUEST, CONVERSATION_IDS.get(1));
        ResponseEntity<String> codeResponse = postQuery(EXECUTE_CODE_REQUEST, CONVERSATION_IDS.get(2));

        assertSuccessfulResultContains(commandResponse, FILE_MARKER);
        assertSuccessfulResultContains(readResponse, FILE_MARKER);
        assertSuccessfulResultContains(codeResponse, CODE_MARKER);
        assertThat(MODEL_TOOL_LISTS).isNotEmpty();
        assertThat(MODEL_TOOL_LISTS.get(0)).contains(EXECUTE_CMD, READ_FILE, EXECUTE_CODE);
        assertThat(MODEL_TOOL_CALLS).extracting(ToolInvocation::name)
            .containsExactly(EXECUTE_CMD, READ_FILE, EXECUTE_CODE);

        SandboxClient governanceClient = sandboxClientFactory.create();
        assertThatThrownBy(() -> governanceClient.shell().executeCmd("sleep 6", ".", 10, Map.of(), Map.of()))
            .isInstanceOf(ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT);
        long circuitStartNanos = System.nanoTime();
        assertThatThrownBy(() -> governanceClient.shell().executeCmd("echo should-not-run", ".", 10,
            Map.of(), Map.of()))
            .isInstanceOf(ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN);
        long circuitElapsedMs = (System.nanoTime() - circuitStartNanos) / 1_000_000L;
        assertThat(circuitElapsedMs).isLessThan(1000L);

        assertThat(output).contains("EXTERNAL_CALL_AUDIT")
            .contains("adapter=Sandbox")
            .contains("success=true")
            .contains("method=shell.executeCmd")
            .contains("method=fs.readFile")
            .contains("method=code.executeCode")
            .contains("success=false")
            .contains(ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT.getCode());
    }

    private ResponseEntity<String> postQuery(String message, String conversationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> request = Map.of(
            "message", message,
            "conversation_id", conversationId,
            "stream", false);
        return rest.postForEntity("/v1/query", new HttpEntity<>(request, headers), String.class);
    }

    private void assertSuccessfulResultContains(ResponseEntity<String> response, String expected) throws IOException {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = MAPPER.readValue(response.getBody(), new TypeReference<>() {
        });
        Object result = body.get("result");
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("content")).asString().contains(expected);
    }

    private static ToolInvocation requestedTool(List<Map<String, Object>> messages) {
        String request = messages.stream()
            .filter(message -> "user".equals(String.valueOf(message.get("role"))))
            .map(message -> String.valueOf(message.get("content")))
            .reduce((first, second) -> second)
            .orElse("");
        if (request.contains(PREPARE_FILE_REQUEST)) {
            return new ToolInvocation(EXECUTE_CMD, Map.of(
                "command", "printf '" + FILE_MARKER + "' | tee " + SANDBOX_PATH,
                "cwd", ".",
                "timeout", 10,
                "environment", Map.of(),
                "options", Map.of()));
        }
        if (request.contains(READ_FILE_REQUEST)) {
            return new ToolInvocation(READ_FILE, Map.of(
                "path", SANDBOX_PATH,
                "mode", "text",
                "encoding", "UTF-8",
                "chunkSize", 0,
                "options", Map.of()));
        }
        if (request.contains(EXECUTE_CODE_REQUEST)) {
            return new ToolInvocation(EXECUTE_CODE, Map.of(
                "code", "print('" + CODE_MARKER + "')",
                "language", "python",
                "timeout", 10,
                "environment", Map.of(),
                "options", Map.of()));
        }
        throw new IllegalArgumentException("Unsupported sandbox E2E request: " + request);
    }

    private static String serialize(Object value, String description) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize " + description, ex);
        }
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
        private SandboxToolCallingModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
            Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs) {
            MODEL_TOOL_LISTS.add(serialize(tools, "sandbox tools"));
            List<Map<String, Object>> convertedMessages = new ArrayList<>(convertMessagesToDict(messages));
            String toolResult = convertedMessages.stream()
                .filter(message -> "tool".equals(String.valueOf(message.get("role"))))
                .map(message -> String.valueOf(message.get("content")))
                .reduce((first, second) -> second)
                .orElse(null);
            if (toolResult != null) {
                return new AssistantMessage("Sandbox tool result: " + toolResult);
            }

            ToolInvocation invocation = requestedTool(convertedMessages);
            MODEL_TOOL_CALLS.add(invocation);
            ToolCall toolCall = ToolCall.builder()
                .id("sandbox-call-" + MODEL_TOOL_CALLS.size())
                .type("function")
                .name(invocation.name())
                .arguments(serialize(invocation.arguments(), "sandbox tool arguments"))
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

    private record ToolInvocation(String name, Map<String, Object> arguments) {
    }
}
