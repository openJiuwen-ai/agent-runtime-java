/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryMvcIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSse(String body) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                String json = trimmed.substring("data:".length()).strip();
                try {
                    events.add(mapper.readValue(json, Map.class));
                } catch (Exception e) {
                    throw new IllegalStateException("bad SSE data line: " + json, e);
                }
            }
        }
        return events;
    }

    private static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseJson(ResponseEntity<String> response) throws Exception {
        return mapper.readValue(response.getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ResponseEntity<String> response) throws Exception {
        return (Map<String, Object>) responseJson(response).get("result");
    }

    @Test
    void streamingQueryReturnsPythonStyleSseChunk() {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("hello")),
                "conversation_id", "c-stream",
                "stream", true);

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(resp.getBody()).contains("data: {");

        List<Map<String, Object>> events = parseSse(resp.getBody());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("role", "assistant");
        assertThat(events.get(0)).containsEntry("content", "turn1:hello");
        assertThat(events.get(0)).doesNotContainKey("events");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamingQueryReturnsAggregatedJson() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("hi")),
                "conversation_id", "c-json",
                "stream", false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("conversation_id", "c-json");
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");
        assertThat(result).containsEntry("content", "turn1:hi");
        assertThat(result).doesNotContainKey("events");
    }

    @Test
    void messagesTakePrecedenceOverMessageField() throws Exception {
        Map<String, Object> body = Map.of(
                "message", "ignored",
                "messages", List.of(userMessage("real")),
                "conversation_id", "c-priority",
                "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:real");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void assistantOnlyMessagesFallBackToLastMessageContent() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", "assistant", "content", "fallback")),
                "conversation_id", "c-fallback",
                "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:fallback");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void emptyMessageListProducesEmptyQuery() throws Exception {
        Map<String, Object> body = Map.of(
                "conversation_id", "c-empty",
                "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:");
        assertThat(result).containsEntry("messages_size", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiTurnRetainsContextForSameConversation() throws Exception {
        Map<String, Object> turn1 = Map.of(
                "messages", List.of(userMessage("a")), "conversation_id", "c-multi", "stream", false);
        Map<String, Object> turn2 = Map.of(
                "messages", List.of(userMessage("b")), "conversation_id", "c-multi", "stream", false);

        Map<String, Object> r1 = mapper.readValue(postQuery("/v1/query", turn1).getBody(), Map.class);
        Map<String, Object> r2 = mapper.readValue(postQuery("/v1/query", turn2).getBody(), Map.class);

        Map<String, Object> result1 = (Map<String, Object>) r1.get("result");
        Map<String, Object> result2 = (Map<String, Object>) r2.get("result");
        assertThat(result1.get("content")).isEqualTo("turn1:a");
        assertThat(result2.get("content")).isEqualTo("turn2:b|prev=a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyPathWorks() throws Exception {
        Map<String, Object> body = Map.of(
                "message", "legacy",
                "conversation_id", "c-legacy",
                "stream", false);

        Map<String, Object> json = mapper.readValue(postQuery("/query", body).getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result.get("content")).isEqualTo("turn1:legacy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantHeadersOverrideBodyFields() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("ctx")),
                "conversation_id", "c-context",
                "user_id", "body-user",
                "space_id", "body-space",
                "tenant_id", "body-tenant",
                "stream", false);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", "header-user");
        headers.set("X-Space-ID", "header-space");
        headers.set("X-Tenant-ID", "header-tenant");

        Map<String, Object> json = mapper.readValue(postQuery("/v1/query", body, headers).getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");

        assertThat(result).containsEntry("user_id", "header-user");
        assertThat(result).containsEntry("space_id", "header-space");
        assertThat(result).containsEntry("tenant_id", "header-tenant");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void blankTenantHeadersDoNotOverrideBodyFields() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("ctx")),
                "conversation_id", "c-blank-context",
                "user_id", "body-user",
                "space_id", "body-space",
                "tenant_id", "body-tenant",
                "stream", false);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", " ");
        headers.set("X-Space-ID", " ");
        headers.set("X-Tenant-ID", " ");

        Map<String, Object> result = result(postQuery("/v1/query", body, headers));

        assertThat(result).containsEntry("user_id", "body-user");
        assertThat(result).containsEntry("space_id", "body-space");
        assertThat(result).containsEntry("tenant_id", "body-tenant");
    }

    @Test
    void missingConversationIdReturnsBadRequest() {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("hi")), "stream", false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankConversationIdReturnsBadRequestBody() throws Exception {
        Map<String, Object> body = Map.of(
                "conversation_id", " ",
                "messages", List.of(userMessage("hi")),
                "stream", false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);
        Map<String, Object> json = responseJson(resp);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }
}
