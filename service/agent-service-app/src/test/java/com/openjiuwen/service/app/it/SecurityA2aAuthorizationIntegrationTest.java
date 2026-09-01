/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.RecordingFineGrainedAuthorizer;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizationRequest;

import org.junit.jupiter.api.Test;
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
 * TC-A2A-02: A2A authorization allow-path and tenant propagation integration tests.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(IngressAuthorizationTestSupport.AllowAnnotatedIngressAuthorizerConfig.class)
@TestPropertySource(properties = {
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityA2aAuthorizationIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingFineGrainedAuthorizer recordingAuthorizer;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowA2aRpcPassesTenantHeadersToSpi() {
        recordingAuthorizer.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", "u1");
        headers.set("X-Space-ID", "s1");
        headers.set("X-Tenant-ID", "t1");
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", "1", "method", "SendMessage", "params",
            Map.of("message",
                Map.of("role", "ROLE_USER", "parts", List.of(Map.of("text", "hello")), "contextId", "ctx-a2a")));

        ResponseEntity<String> response = rest.postForEntity(A2AServicePaths.A2A_JSONRPC,
            new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("ACCESS_DENIED");
        assertThat(recordingAuthorizer.requests()).hasSize(1);
        AuthorizationRequest authRequest = recordingAuthorizer.requests().get(0);
        assertThat(authRequest.resource()).isEqualTo("a2a");
        assertThat(authRequest.action()).isEqualTo("rpc");
        assertThat(authRequest.userId()).isEqualTo("u1");
        assertThat(authRequest.spaceId()).isEqualTo("s1");
        assertThat(authRequest.tenantId()).isEqualTo("t1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessFailureReturnsJsonRpcErrorNot403() throws Exception {
        recordingAuthorizer.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", "u1");
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", "biz-err", "method", "SendMessage", "params",
            Map.of("message", Map.of("role", "ROLE_USER", "parts",
                List.of(Map.of("text", MultiTurnEchoHandler.SYNC_FAILURE_QUERY)), "contextId", "ctx-a2a-fail")));

        ResponseEntity<String> response = rest.postForEntity(A2AServicePaths.A2A_JSONRPC,
            new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        assertThat(json).containsEntry("jsonrpc", "2.0").containsEntry("id", "biz-err");
        assertThat(response.getBody()).doesNotContain("ACCESS_DENIED");
        assertThat(json).doesNotContainKey("code");
        assertThat(json.containsKey("error") || json.containsKey("result")).isTrue();
    }
}
