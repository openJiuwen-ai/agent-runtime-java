/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentServiceAutoConfigurationMvcIntegrationTest
 *
 * @since 2026-07-03
 */
@SpringBootTest(classes = AgentServiceAutoConfigurationMvcIntegrationTest.MinimalAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "openjiuwen.service.query.webflux.enabled=false")
@AutoConfigureTestRestTemplate
class AgentServiceAutoConfigurationMvcIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void autoConfigurationExposesQueryEndpointWithoutBusinessController() throws Exception {
        ResponseEntity<String> resp = postQuery("/v1/query",
                Map.of("message", "hello", "conversation_id", "c-auto", "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("content", "custom:hello");
        assertThat(result).containsEntry("handler", "custom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyQueryPathUsesSameAutoConfiguredChain() throws Exception {
        ResponseEntity<String> resp = postQuery("/query",
                Map.of("messages", List.of(Map.of("role", "user", "content", "legacy")), "conversation_id",
                        "c-auto-legacy", "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("content", "custom:legacy");
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalAgentApplication {
        @Bean
        AgentHandler customAgentHandler() {
            return new CustomAgentHandler();
        }
    }

    static class CustomAgentHandler implements AgentHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public QueryResponse query(ServeRequest request) {
            calls.incrementAndGet();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", "assistant");
            result.put("content", "custom:" + request.lastUserQuery());
            result.put("handler", "custom");
            return new QueryResponse(result, request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", "assistant");
            payload.put("content", "custom:" + request.lastUserQuery());
            payload.put("handler", "custom");
            observer.onNext(new QueryChunk("chunk", payload));
            observer.onComplete();
        }
    }
}
