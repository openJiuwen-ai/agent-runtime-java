/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * DefaultAgentReadinessIntegrationTest
 *
 * @since 2026-07-03
 */
class DefaultAgentReadinessIntegrationTest {

    @Nested
    @SpringBootTest(classes = NoHandlerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    class WhenNoAgentHandler {

        @Autowired
        private TestRestTemplate rest;

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @SuppressWarnings("unchecked")
        void healthReportsAgentLoadedFalse() throws Exception {
            ResponseEntity<String> resp = rest.getForEntity(AgentServicePaths.HEALTH, String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
            assertThat(json).containsEntry("process_up", true);
            assertThat(json).containsEntry("agent_loaded", false);
        }
    }

    @Nested
    @SpringBootTest(classes = HandlerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    class WhenAgentHandlerExists {

        @Autowired
        private TestRestTemplate rest;

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @SuppressWarnings("unchecked")
        void healthReportsAgentLoadedTrueWithoutCallingQuery() throws Exception {
            ResponseEntity<String> resp = rest.getForEntity(AgentServicePaths.HEALTH, String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
            assertThat(json).containsEntry("process_up", true);
            assertThat(json).containsEntry("agent_loaded", true);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class NoHandlerApplication {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class HandlerApplication {

        @Bean
        AgentHandler failingIfQueriedAgentHandler() {
            return new FailingIfQueriedAgentHandler();
        }
    }

    static class FailingIfQueriedAgentHandler implements AgentHandler {

        @Override
        public QueryResponse query(ServeRequest request) {
            throw new AssertionError("/health must not call query");
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk("error", Map.of("error", "should not be called")));
            throw new AssertionError("/health must not call streamQuery");
        }
    }
}
