/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.assertAccessDenied;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * TC-AUTHZ-02: fine-grained authorization deny-path integration tests against
 * {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(IngressAuthorizationTestSupport.DenyQueryAuthorizerConfig.class)
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
        assertAccessDenied(mapper, response, "query", "execute", "policy denied");
    }
}
