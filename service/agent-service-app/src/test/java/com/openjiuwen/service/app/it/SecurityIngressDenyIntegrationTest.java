/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.assertAccessDenied;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.paths.A2AServicePaths;

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
 * TC-AUTHZ-03/04 and TC-A2A-01 deny-path integration tests for annotated ingress
 * endpoints against {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(IngressAuthorizationTestSupport.DenyAnnotatedIngressAuthorizerConfig.class)
@TestPropertySource(properties = {
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityIngressDenyIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void denyAgentCardReturns403Contract() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(A2AServicePaths.WELL_KNOWN_AGENT_CARD, String.class);
        assertAccessDenied(mapper, response, "agent-card", "read", "policy denied");
    }

    @Test
    void denyResetConversationReturns403Contract() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(AgentServicePaths.RESET_CONVERSATION_V1,
            new HttpEntity<>(Map.of("conversation_id", "conv-reset-deny"), headers), String.class);
        assertAccessDenied(mapper, response, "session", "reset", "policy denied");
    }

    @Test
    void denyA2aRpcReturns403Contract() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", "1", "method", "SendMessage", "params",
            Map.of("message",
                Map.of("role", "ROLE_USER", "parts", List.of(Map.of("text", "hello")), "contextId", "ctx-a2a-deny")));
        ResponseEntity<String> response = rest.postForEntity(A2AServicePaths.A2A_JSONRPC,
            new HttpEntity<>(body, headers), String.class);
        assertAccessDenied(mapper, response, "a2a", "rpc", "policy denied");
    }
}
