/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = QueryAutoConfigurationDisabledIntegrationTest.MinimalAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"openjiuwen.service.query.enabled=false",
        "openjiuwen.service.query.webflux.enabled=false"})
@AutoConfigureTestRestTemplate
class QueryAutoConfigurationDisabledIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void queryEnabledPropertyDoesNotPreventQueryEndpointAutoRegistration() {
        ResponseEntity<String> resp = postQuery("/v1/query",
                Map.of("message", "hello", "conversation_id", "c-query-disabled", "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalAgentApplication {

        @Bean
        AgentHandler customAgentHandler() {
            return new DisabledQueryAgentHandler();
        }
    }

    static class DisabledQueryAgentHandler implements AgentHandler {

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("content", "query-disabled"), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk("chunk", Map.of("content", "query-disabled")));
            observer.onComplete();
        }
    }
}
