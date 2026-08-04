/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

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

import java.util.Map;

/**
 * Verifies reset legacy path registration follows {@code legacy-path-enabled}.
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "openjiuwen.service.query.legacy-path-enabled=false")
@AutoConfigureTestRestTemplate
class ResetPathIsolationIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void legacyResetPathIsNotRegisteredWhileV1RemainsAvailable() throws Exception {
        ResponseEntity<String> legacyResp = post(AgentServicePaths.RESET_CONVERSATION_LEGACY,
            Map.of("conversation_id", "legacy-reset-c1"));
        assertThat(legacyResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> v1Resp = post(AgentServicePaths.RESET_CONVERSATION_V1,
            Map.of("conversation_id", "v1-reset-c1"));
        assertThat(v1Resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(v1Resp.getBody(), Map.class);
        assertThat(json).containsEntry("status", "ok");
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
