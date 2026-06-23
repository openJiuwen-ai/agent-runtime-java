/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFlux {@code /v1/query/reactive} integration tests against {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.main.web-application-type=reactive",
        "openjiuwen.service.query.webflux.enabled=true"
})
class QueryWebFluxIntegrationTest {
    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    @Test
    void streamingQueryReturnsPythonStyleSseChunk() {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("flux")),
                "conversation_id", "c-flux",
                "stream", true);

        byte[] responseBody = webTestClient.post()
                .uri("/v1/query/reactive")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .returnResult()
                .getResponseBody();

        String text = new String(responseBody);
        assertThat(text).contains("data: {");
        assertThat(text).contains("\"role\":\"assistant\"");
        assertThat(text).contains("turn1:flux");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamingQueryReturnsAggregatedJson() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("json")),
                "conversation_id", "c-flux-json",
                "stream", false);

        byte[] bytes = webTestClient.post()
                .uri("/v1/query/reactive")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result.get("content")).isEqualTo("turn1:json");
        assertThat(result).doesNotContainKey("events");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankConversationIdReturnsBadRequestBody() throws Exception {
        Map<String, Object> body = Map.of(
                "messages", List.of(userMessage("blank")),
                "conversation_id", " ",
                "stream", false);

        byte[] bytes = webTestClient.post()
                .uri("/v1/query/reactive")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }
}
