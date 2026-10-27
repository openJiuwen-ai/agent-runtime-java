/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies that terminal failures close MVC SSE responses without aborting the wire body.
 */
@SpringBootTest(classes = SseErrorTerminalIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(10)
class SseErrorTerminalIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void structuredErrorEventCompletesWithoutDuplicateError() throws Exception {
        String body = postAndRead("structured");

        List<Map<String, Object>> events = errorEvents(body);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("error", "structured boom");
    }

    @Test
    void terminalFailureWithoutErrorEventGetsSseFallback() throws Exception {
        String body = postAndRead("uncovered");

        List<Map<String, Object>> events = errorEvents(body);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("type", "error")
                .containsEntry("code", "AGENT_EXECUTION_FAILED")
                .containsEntry("error", "agent execution failed")
                .containsEntry("conversation_id", "c-uncovered");
        assertThat(body).doesNotContain("uncovered boom");
    }

    private String postAndRead(String query) throws Exception {
        String json = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + query + "\"}],"
                + "\"conversation_id\":\"c-" + query + "\",\"stream\":true}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> errorEvents(String body) throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                Map<String, Object> event = mapper.readValue(trimmed.substring(5).strip(), Map.class);
                if ("error".equals(event.get("type"))) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        @Primary
        AgentHandler errorTerminalHandler() {
            return new ErrorTerminalHandler();
        }
    }

    private static final class ErrorTerminalHandler implements AgentHandler {
        /**
         * Emits the requested terminal stream scenario.
         *
         * @param request query selecting the scenario
         * @param observer downstream stream observer
         */
        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            String query = request.lastUserQuery();
            if ("structured".equals(query)) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR,
                        Map.of("type", "error", "error", "structured boom")));
                observer.onError(new IllegalStateException("structured boom"));
                return;
            }
            observer.onError(new IllegalStateException("uncovered boom"));
        }

        /**
         * Rejects synchronous queries because this fixture only tests streaming.
         *
         * @param request unused synchronous request
         * @return no response; this fixture always rejects synchronous queries
         */
        @Override
        public QueryResponse query(ServeRequest request) {
            throw new IllegalStateException("synchronous query is not used");
        }
    }
}
