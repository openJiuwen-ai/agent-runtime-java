/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Integration test for the degraded active task query endpoint (DFX-002 S-25):
 * when no {@code ActiveTaskQuery} bean exists (ext module not loaded), the
 * endpoint must return 200 with an empty zero-task snapshot instead of failing.
 *
 * <p>This test deliberately uses its own application context WITHOUT an
 * {@code ActiveTaskQuery} bean so the controller's degraded branch is actually
 * reachable — sharing a context that registers the bean would make the
 * degradation path untestable.
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ActiveTaskQueryDegradedIntegrationTest.DegradedTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ActiveTaskQueryDegradedIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void endpoint_returns200_degraded_whenNoQueryBean() {
        ResponseEntity<Map> response = rest.getForEntity(
                "http://localhost:" + port + "/v1/current_active_tasks", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        // Degraded contract: unlimited (-1), zero active tasks, empty list
        assertThat(body).containsEntry("maxConcurrentTasks", -1);
        assertThat(body).containsEntry("currentActiveTasks", 0);
        assertThat((List<Map<String, Object>>) body.get("tasks")).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class DegradedTestApp {
        // No ActiveTaskQuery bean: simulates the ext module not being loaded
        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }
    }
}
