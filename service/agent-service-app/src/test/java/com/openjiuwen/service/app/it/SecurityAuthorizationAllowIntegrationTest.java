/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.RecordingFineGrainedAuthorizer;
import com.openjiuwen.service.app.it.support.AgentReadinessTestSupport;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
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

/**
 * TC-AUTHZ-05: SPI allow-path integration tests for {@link TestServiceApplication}.
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
class SecurityAuthorizationAllowIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingFineGrainedAuthorizer recordingAuthorizer;

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
    void allowQueryPassesTenantHeadersToSpiAndBusinessLayer() throws Exception {
        recordingAuthorizer.clear();
        Map<String, Object> body = Map.of("conversation_id", "conv-auth-allow", "messages",
            List.of(Map.of("role", "user", "content", "hello")), "stream", false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", "u1");
        headers.set("X-Space-ID", "s1");
        headers.set("X-Tenant-ID", "t1");

        ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("ACCESS_DENIED");

        List<AuthorizationRequest> requests = recordingAuthorizer.requests();
        assertThat(requests).hasSize(1);
        AuthorizationRequest authRequest = requests.get(0);
        assertThat(authRequest.resource()).isEqualTo("query");
        assertThat(authRequest.action()).isEqualTo("execute");
        assertThat(authRequest.userId()).isEqualTo("u1");
        assertThat(authRequest.spaceId()).isEqualTo("s1");
        assertThat(authRequest.tenantId()).isEqualTo("t1");

        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("user_id", "u1");
        assertThat(result).containsEntry("space_id", "s1");
        assertThat(result).containsEntry("tenant_id", "t1");
    }
}
