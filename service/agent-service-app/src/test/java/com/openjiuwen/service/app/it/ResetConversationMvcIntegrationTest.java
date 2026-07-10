/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

import org.junit.jupiter.api.Tag;
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
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

/**
 * ResetConversationMvcIntegrationTest
 *
 * @since 2026-07-03
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ResetConversationMvcIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DefaultAgentReadiness readiness;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @Tag("smoke")
    void resetClearsMultiTurnContextForSameConversationId() throws Exception {
        String path = "/v1/query";
        String conversationId = "reset-multi-c1";

        ResponseEntity<String> first = postJson(path, queryBody("hello", conversationId));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> firstJson = mapper.readValue(first.getBody(), Map.class);
        assertThat(firstJson.get("result")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) firstJson.get("result")).get("content")).isEqualTo("turn1:hello");

        ResponseEntity<String> second = postJson(path, queryBody("again", conversationId));
        Map<String, Object> secondJson = mapper.readValue(second.getBody(), Map.class);
        assertThat(((Map<?, ?>) secondJson.get("result")).get("content")).asString().contains("prev=hello");

        ResponseEntity<String> reset = postJson(AgentServicePaths.RESET_CONVERSATION_V1,
            Map.of("conversation_id", conversationId));
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> resetJson = mapper.readValue(reset.getBody(), Map.class);
        assertThat(resetJson).containsEntry("status", "ok");
        assertThat(resetJson.get("message")).asString().contains(conversationId);

        ResponseEntity<String> third = postJson(path, queryBody("fresh", conversationId));
        Map<String, Object> thirdJson = mapper.readValue(third.getBody(), Map.class);
        assertThat(((Map<?, ?>) thirdJson.get("result")).get("content")).isEqualTo("turn1:fresh");
    }

    @Test
    void missingConversationIdReturns400() {
        ResponseEntity<String> response = postJson(AgentServicePaths.RESET_CONVERSATION_V1, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankConversationIdReturns400WithErrorBody() throws Exception {
        ResponseEntity<String> response = postJson(AgentServicePaths.RESET_CONVERSATION_V1,
            Map.of("conversation_id", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyResetPathReturnsOk() throws Exception {
        ResponseEntity<String> response = postJson(AgentServicePaths.RESET_CONVERSATION_LEGACY,
            Map.of("conversation_id", "legacy-c1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        assertThat(json).containsEntry("status", "ok");
        assertThat(json.get("message")).asString().contains("legacy-c1");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void resetReturns503WhenAgentNotReady() {
        readiness.markShuttingDown();

        ResponseEntity<String> response = postJson(AgentServicePaths.RESET_CONVERSATION_V1,
            Map.of("conversation_id", "shutdown-c1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static Map<String, Object> queryBody(String message, String conversationId) {
        return Map.of("message", message, "conversation_id", conversationId, "stream", false);
    }

    private ResponseEntity<String> postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
