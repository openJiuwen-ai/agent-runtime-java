/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.AgentReadinessTestSupport;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * TC-AUTHZ-06: verifies 400/503 business errors remain unchanged when auth is enabled.
 *
 * @since 0.1.0
 */
class SecurityBusinessErrorRegressionIntegrationTest {
    private static HttpHeaders tenantHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", "u1");
        headers.set("X-Space-ID", "s1");
        headers.set("X-Tenant-ID", "t1");
        return headers;
    }

    @Nested
    @SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    @Import(IngressAuthorizationTestSupport.AllowQueryAuthorizerConfig.class)
    @TestPropertySource(properties = {
        "openjiuwen.service.security.enabled=true",
        "openjiuwen.service.security.auth.enabled=true"
    })
    class WithLoadedAgent {
        @Autowired
        private TestRestTemplate rest;

        @Autowired
        private DefaultAgentReadiness readiness;

        @Autowired
        private ObjectProvider<AgentHandler> agentHandlerProvider;

        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void ensureAgentLoaded() {
            AgentReadinessTestSupport.ensureAgentLoaded(readiness, agentHandlerProvider);
        }

        @Test
        @SuppressWarnings("unchecked")
        void missingConversationIdStillReturns400WhenAuthAllows() throws Exception {
            HttpHeaders headers = tenantHeaders();
            Map<String, Object> body = Map.of("messages", List.of(Map.of("role", "user", "content", "hi")), "stream",
                false);

            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
                String.class);
            Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json).containsEntry("type", "error");
            assertThat(json).containsEntry("error", "conversation_id is required");
            assertThat(json).doesNotContainKey("code");
        }
    }

    @Nested
    @SpringBootTest(classes = AgentNotLoadedWithAuthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    @Import(IngressAuthorizationTestSupport.AllowQueryAuthorizerConfig.class)
    @TestPropertySource(properties = {
        "openjiuwen.service.security.enabled=true",
        "openjiuwen.service.security.auth.enabled=true",
        "openjiuwen.service.query.webflux.enabled=false"
    })
    class WithAgentNotLoaded {
        @Autowired
        private TestRestTemplate rest;

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @SuppressWarnings("unchecked")
        void agentNotLoadedStillReturns503WhenAuthAllows() throws Exception {
            HttpHeaders headers = tenantHeaders();
            Map<String, Object> body = Map.of("message", "blocked", "conversation_id", "c-not-loaded-auth", "stream",
                false);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
                String.class);
            Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(json).containsEntry("type", "error");
            assertThat(json).containsEntry("error", "agent not loaded");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class AgentNotLoadedWithAuthApplication {
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
