/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

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
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.service.spec.spi.AgentHandler;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test for the demo service surface: {@code /health},
 * {@code /v1/query}
 * and {@code /v1/reset_conversation}, all served by the agent-core-java
 * handler. A deterministic
 * in-memory model stands in for a real LLM so the assertions stay stable.
 */
@SpringBootTest(classes = DemoAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoAgentApplicationTest {

    private static final String TEST_PROVIDER = "DemoSmokeProvider";
    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.demo.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.demo.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.demo.llm.api-base", () -> "mirror://demo-smoke");
        registry.add("openjiuwen.demo.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.demo.llm.auto-discover", () -> "false");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new EchoModelFactory());
        }
    }

    @AfterAll
    void cleanupRunner() {
        Runner.release("demo-c1");
        agentHandler.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoApplicationServesHealthApi() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity("/health", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("app", "demo-agent-service");
        assertThat(json).containsEntry("version", "0.1.0");
        assertThat(json).containsEntry("process_up", true);
        assertThat(json).containsEntry("agent_loaded", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoApplicationServesQueryApiViaCoreHandler() throws Exception {
        ResponseEntity<String> resp = postJson("/v1/query",
                Map.of("message", "hello", "conversation_id", "demo-c1", "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("conversation_id", "demo-c1");
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");
        assertThat(result.get("content")).asString().contains("echo:hello");
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoApplicationServesResetConversationApi() throws Exception {
        postJson("/v1/query", Map.of("message", "hello", "conversation_id", "demo-c1", "stream", false));

        ResponseEntity<String> reset = postJson("/v1/reset_conversation", Map.of("conversation_id", "demo-c1"));

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(reset.getBody(), Map.class);
        assertThat(json).containsEntry("status", "ok");
        assertThat(json.get("message")).asString().contains("demo-c1");
    }

    private ResponseEntity<String> postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private static final class EchoModelFactory implements Model.ModelClientFactory {

        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new EchoModelClient(modelConfig, clientConfig);
        }
    }

    private static final class EchoModelClient extends BaseModelClient {

        private EchoModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            String lastUser = convertMessagesToDict(messages).stream()
                    .filter(message -> "user".equals(String.valueOf(message.get("role"))))
                    .map(message -> String.valueOf(message.get("content"))).reduce((first, second) -> second)
                    .orElse("");
            return new AssistantMessage("echo:" + lastUser);
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
                String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            return List.<AssistantMessageChunk>of().iterator();
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
                String negativePrompt, int n, boolean promptExtend, boolean watermark, int seed,
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
                String model, String size, String resolution, int duration, boolean promptExtend, boolean watermark,
                String negativePrompt, Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }
}
