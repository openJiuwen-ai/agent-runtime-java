/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.AgentReadinessTestSupport;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.RecordingFineGrainedAuthorizer;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * TC-AUTHZ-07: concurrent authorization requests remain isolated per thread.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(IngressAuthorizationTestSupport.AllowQueryAuthorizerConfig.class)
@TestPropertySource(properties = {
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityAuthorizationConcurrencyIntegrationTest {
    private static final int CONCURRENCY = 20;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingFineGrainedAuthorizer recordingAuthorizer;

    @Autowired
    private DefaultAgentReadiness readiness;

    @Autowired
    private ObjectProvider<AgentHandler> agentHandlerProvider;

    @BeforeEach
    void ensureAgentLoaded() {
        AgentReadinessTestSupport.ensureAgentLoaded(readiness, agentHandlerProvider);
    }

    @Test
    void concurrentRequestsKeepAuthorizationContextIsolated() {
        recordingAuthorizer.clear();
        List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENCY)
            .mapToObj(index -> CompletableFuture.runAsync(() -> invokeQuery("u" + index, "conv-" + index)))
            .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        assertThat(recordingAuthorizer.requests()).hasSize(CONCURRENCY);
        for (int index = 0; index < CONCURRENCY; index++) {
            String expectedUser = "u" + index;
            assertThat(recordingAuthorizer.requests()).anyMatch(
                request -> expectedUser.equals(request.userId()) && "query".equals(request.resource()));
        }
    }

    private void invokeQuery(String userId, String conversationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", userId);
        Map<String, Object> body = Map.of("conversation_id", conversationId, "messages",
            List.of(Map.of("role", "user", "content", "hello")), "stream", false);
        ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
            String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
