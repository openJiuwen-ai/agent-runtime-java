/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * Integration tests for fine-grained authorization on ingress REST endpoints.
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(SecurityAuthorizationIntegrationTest.DenyQueryAuthorizerConfig.class)
@TestPropertySource(properties = {
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityAuthorizationIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void denyQueryReturns403Contract() throws Exception {
        Map<String, Object> body = Map.of("conversation_id", "conv-auth-deny", "messages",
            List.of(Map.of("role", "user", "content", "hello")));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
            String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = mapper.readValue(response.getBody(), Map.class);
        assertThat(error.get("type")).isEqualTo("error");
        assertThat(error.get("code")).isEqualTo("ACCESS_DENIED");
        assertThat(error.get("resource")).isEqualTo("query");
        assertThat(error.get("action")).isEqualTo("execute");
        assertThat(error.get("reason")).isEqualTo("policy denied");
    }

    @TestConfiguration
    static class DenyQueryAuthorizerConfig {
        /**
         * Denies query execute for integration testing.
         *
         * @return authorizer bean
         */
        @Bean
        FineGrainedAuthorizer fineGrainedAuthorizer() {
            return request -> "query".equals(request.resource()) && "execute".equals(request.action())
                ? AuthorizationResult.deny("policy denied")
                : AuthorizationResult.allow();
        }
    }
}
