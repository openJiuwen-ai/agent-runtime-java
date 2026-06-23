/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.app.controller.query.QueryMvcController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies MVC query returns service unavailable when no orchestrator is configured.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = QueryMvcNoOrchestratorIntegrationTest.QueryOnlyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class QueryMvcNoOrchestratorIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void queryReturnsServiceUnavailableWhenNoOrchestratorIsConfigured() throws Exception {
        ResponseEntity<String> response = postQuery("/v1/query", Map.of(
                "message", "blocked",
                "conversation_id", "c-no-orchestrator",
                "stream", false));

        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "no agent handler configured");
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = AgentServiceAutoConfiguration.class)
    @Import(QueryMvcController.class)
    static class QueryOnlyApplication {
    }
}
