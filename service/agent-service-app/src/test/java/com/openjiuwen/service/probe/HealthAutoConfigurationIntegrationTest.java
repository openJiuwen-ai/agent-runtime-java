/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * HealthAutoConfigurationIntegrationTest
 *
 * @since 2026-07-03
 */
@SpringBootTest(classes = HealthAutoConfigurationIntegrationTest.MinimalAgentApplication.class, properties = {
        "spring.application.name=probe-test-app",
        "openjiuwen.service.version=1.2.3-test"}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HealthAutoConfigurationIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DefaultAgentReadiness readiness;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void healthReturnsProcessUpAndAgentLoadedFalseBeforeInit() throws Exception {
        readiness.markAgentLoaded(false);

        ResponseEntity<String> resp = rest.getForEntity(AgentServicePaths.HEALTH, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("process_up", true);
        assertThat(json).containsEntry("agent_loaded", false);
        assertThat(json).containsEntry("app", "probe-test-app");
        assertThat(json).containsEntry("version", "1.2.3-test");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthReturnsAgentLoadedTrueAfterInit() throws Exception {
        readiness.markAgentLoaded(true);

        ResponseEntity<String> resp = rest.getForEntity(AgentServicePaths.HEALTH, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("process_up", true);
        assertThat(json).containsEntry("agent_loaded", true);
        assertThat(json).containsEntry("version", "1.2.3-test");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MinimalAgentApplication {
    }
}
