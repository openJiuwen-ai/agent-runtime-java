package com.openjiuwen.service.demo;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoAgentApplication.class,
        properties = "openjiuwen.demo.llm.enabled=false",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoAgentApplicationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void demoApplicationStartsAndServesQueryApi() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.postForEntity("/v1/query", new HttpEntity<>(Map.of(
                "message", "hello",
                "conversation_id", "demo-c1",
                "stream", false), headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("content", "demo:hello");
    }
}
