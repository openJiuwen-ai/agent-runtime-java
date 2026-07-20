/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for the security demo module against
 * {@link SecurityDemoApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = SecurityDemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
    "openjiuwen.service.llm.auto-discover=false",
    "openjiuwen.service.llm.api-key=demo-test-key",
    "openjiuwen.service.llm.api-base=http://127.0.0.1:9/v1",
    "openjiuwen.service.llm.model-name=demo-test-model"
})
class SecurityDemoApplicationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void healthIsNotProtectedByAuthorizationAspect() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void queryWithoutUserIdReturns403Contract() throws Exception {
        Map<String, Object> body = Map.of("conversation_id", "sec-demo-deny", "messages",
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
        assertThat(error.get("reason")).isEqualTo("X-User-ID header is required");
    }
}
