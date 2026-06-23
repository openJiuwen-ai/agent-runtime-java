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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies reactive query returns service unavailable when the agent is not loaded.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = QueryWebFluxUnavailableIntegrationTest.AgentNotLoadedApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.main.web-application-type=reactive",
        "openjiuwen.service.query.webflux.enabled=true"
})
class QueryWebFluxUnavailableIntegrationTest {
    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void reactiveQueryReturnsServiceUnavailableWhenAgentIsNotLoaded() throws Exception {
        byte[] bytes = webTestClient.post()
                .uri("/v1/query/reactive")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "message", "blocked",
                        "conversation_id", "c-flux-not-loaded",
                        "stream", false))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "agent not loaded");
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
