/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HealthReadinessStateIntegrationTest.MinimalAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthReadinessStateIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DefaultAgentReadiness readiness;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @SuppressWarnings("unchecked")
    void healthReportsProcessDownAndAgentNotLoadedWhenShuttingDown() throws Exception {
        readiness.markAgentLoaded(true);
        readiness.markShuttingDown();

        ResponseEntity<String> response = rest.getForEntity(AgentServicePaths.HEALTH, String.class);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("process_up", false);
        assertThat(json).containsEntry("agent_loaded", false);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @SuppressWarnings("unchecked")
    void healthReportsProcessDownAndAgentNotLoadedWhenProcessDown() throws Exception {
        readiness.markAgentLoaded(true);
        readiness.markProcessDown();

        ResponseEntity<String> response = rest.getForEntity(AgentServicePaths.HEALTH, String.class);
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("process_up", false);
        assertThat(json).containsEntry("agent_loaded", false);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalAgentApplication {
    }
}
