/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DemoAgentLlmApplicationTest
 *
 * @since 2026-07-03
 */
@SpringBootTest(classes = DemoAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoAgentLlmApplicationTest {
    private static final String TEST_PROVIDER = "DemoCoreMemoryProvider";

    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);

    private static final List<List<Map<String, Object>>> MODEL_MESSAGES = new CopyOnWriteArrayList<>();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.demo.llm.enabled", () -> "true");
        registry.add("openjiuwen.demo.llm.provider", () -> TEST_PROVIDER);
        registry.add("openjiuwen.demo.llm.api-key", () -> "test-key");
        registry.add("openjiuwen.demo.llm.api-base", () -> "mirror://demo-core-memory");
        registry.add("openjiuwen.demo.llm.model-name", () -> "test-model");
        registry.add("openjiuwen.demo.llm.auto-discover", () -> "false");
    }

    @BeforeAll
    static void registerModelFactory() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new MemoryModelFactory());
        }
    }

    @AfterAll
    void cleanupRunner() {
        Runner.release("memory-c1");
        agentHandler.stop();
        MODEL_MESSAGES.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredLlmUsesCoreSessionForMultiTurnContext() throws Exception {
        ResponseEntity<String> first = postQuery(
            Map.of("message", "????", "conversation_id", "memory-c1", "stream", false));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = postQuery(
            Map.of("message", "????", "conversation_id", "memory-c1", "stream", false));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(second.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");
        assertThat(result.get("content")).asString().contains("??");

        assertThat(MODEL_MESSAGES).hasSizeGreaterThanOrEqualTo(2);
        List<Map<String, Object>> secondCallMessages = MODEL_MESSAGES.get(MODEL_MESSAGES.size() - 1);
        assertThat(secondCallMessages).extracting(message -> String.valueOf(message.get("content")))
            .contains("????", "????");
    }

    private ResponseEntity<String> postQuery(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(body, headers), String.class);
    }

    private static final class MemoryModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new MemoryModelClient(modelConfig, clientConfig);
        }
    }

    private static final class MemoryModelClient extends BaseModelClient {
        private MemoryModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
            Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs) {
            List<Map<String, Object>> converted = new ArrayList<>(convertMessagesToDict(messages));
            MODEL_MESSAGES.add(converted);
            String joined = converted.stream()
                .map(message -> String.valueOf(message.get("content")))
                .reduce("", (left, right) -> left + "\n" + right);
            if (joined.contains("????") && joined.contains("????")) {
                return new AssistantMessage("????");
            }
            return new AssistantMessage("?????");
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
