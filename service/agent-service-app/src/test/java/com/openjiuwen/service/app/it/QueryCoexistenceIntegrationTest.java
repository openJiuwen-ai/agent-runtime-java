/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(classes = TestServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "openjiuwen.service.query.webflux.enabled=true")
@AutoConfigureTestRestTemplate
class QueryCoexistenceIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mvcAndWebFluxEndpointsCoexistOnServletStack() throws Exception {
        Map<String, Object> mvcBody = Map.of(
                "messages", List.of(userMessage("mvc")),
                "conversation_id", "c-coexist-mvc",
                "stream", false);
        Map<String, Object> webFluxBody = Map.of(
                "messages", List.of(userMessage("flux")),
                "conversation_id", "c-coexist-flux",
                "stream", false);

        ResponseEntity<String> mvcResp = postQuery("/v1/query", mvcBody);
        assertThat(mvcResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> mvcJson = mapper.readValue(mvcResp.getBody(), Map.class);
        Map<String, Object> mvcResult = (Map<String, Object>) mvcJson.get("result");
        assertThat(mvcResult.get("content")).isEqualTo("turn1:mvc");

        ResponseEntity<String> webFluxResp = postQuery("/v1/query/reactive", webFluxBody);
        assertThat(webFluxResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> webFluxJson = mapper.readValue(webFluxResp.getBody(), Map.class);
        Map<String, Object> webFluxResult = (Map<String, Object>) webFluxJson.get("result");
        assertThat(webFluxResult.get("content")).isEqualTo("turn1:flux");
    }
}
