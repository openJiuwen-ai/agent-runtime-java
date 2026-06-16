/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = QueryMvcUnavailableIntegrationTest.AgentNotLoadedApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "openjiuwen.service.query.webflux.enabled=false")
class QueryMvcUnavailableIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void queryReturnsServiceUnavailableWhenAgentIsNotLoaded() throws Exception {
        ResponseEntity<String> response = postQuery("/v1/query", Map.of(
                "message", "blocked",
                "conversation_id", "c-not-loaded",
                "stream", false));

        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "agent not loaded");
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class AgentNotLoadedApplication {

        @Bean
        AgentHandler agentHandler() {
            return new UnusedAgentHandler();
        }

        @Bean
        AgentLifecycleManager noOpAgentLifecycleManager() {
            return new AgentLifecycleManager() {
                @Override
                public void runInitPhase() {
                    // Keep DefaultAgentReadiness.agent_loaded=false.
                }

                @Override
                public void runShutdownPhase() {
                    // No resources to release in this test.
                }

                @Override
                public void interrupt(String conversationId) {
                    // No active stream is started while readiness is false.
                }
            };
        }
    }

    static class UnusedAgentHandler implements AgentHandler {

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("content", "should-not-run"), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk("chunk", Map.of("content", "should-not-run")));
            observer.onComplete();
        }
    }
}
