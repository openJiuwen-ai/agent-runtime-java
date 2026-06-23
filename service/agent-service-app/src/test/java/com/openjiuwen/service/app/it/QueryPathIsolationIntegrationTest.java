/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies MVC and WebFlux Query endpoints map to distinct paths without shadowing.
 */
@SpringBootTest(classes = TestServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "openjiuwen.service.query.webflux.enabled=true")
@AutoConfigureTestRestTemplate
class QueryPathIsolationIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mainAndReactivePathsAreIsolatedWhenBothEnabled() throws Exception {
        ResponseEntity<String> mainResp = post(AgentServicePaths.QUERY_V1, body("main", "c-main"));
        assertThat(mainResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result(mainResp).get("content")).isEqualTo("turn1:main");

        ResponseEntity<String> legacyResp = post(AgentServicePaths.QUERY_LEGACY, body("legacy", "c-legacy"));
        assertThat(legacyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result(legacyResp).get("content")).isEqualTo("turn1:legacy");

        ResponseEntity<String> reactiveResp = post(AgentServicePaths.QUERY_V1_REACTIVE, body("reactive", "c-reactive"));
        assertThat(reactiveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result(reactiveResp).get("content")).isEqualTo("turn1:reactive");
    }

    @Nested
    @TestPropertySource(properties = "openjiuwen.service.query.webflux.enabled=false")
    @AutoConfigureTestRestTemplate
    class WhenWebFluxDisabled {

        @Autowired
        private TestRestTemplate rest;

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void reactivePathIsNotRegisteredWhileMainPathRemainsAvailable() throws Exception {
            Map<String, Object> body = body("mvc-only", "c-mvc-only");

            ResponseEntity<String> mainResp = post(rest, AgentServicePaths.QUERY_V1, body);
            assertThat(mainResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result(mainResp, mapper).get("content")).isEqualTo("turn1:mvc-only");

            ResponseEntity<String> reactiveResp = post(rest, AgentServicePaths.QUERY_V1_REACTIVE, body);
            assertThat(reactiveResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static Map<String, Object> body(String content, String conversationId) {
        return Map.of(
                "messages", List.of(Map.of("role", "user", "content", content)),
                "conversation_id", conversationId,
                "stream", false);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return post(rest, path, body);
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ResponseEntity<String> response) throws Exception {
        return result(response, mapper);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(ResponseEntity<String> response, ObjectMapper mapper)
            throws Exception {
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        return (Map<String, Object>) json.get("result");
    }
}
