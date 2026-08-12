/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.probe.ActiveTaskController;
import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;

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
 * Integration tests for the active task query endpoint (DFX-002 S-23~S-25).
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ActiveTaskQueryIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ActiveTaskQueryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void endpoint_returns200_withSnapshot() {
        ResponseEntity<Map> response = rest.getForEntity(
                "http://localhost:" + port + "/v1/current_active_tasks", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("maxConcurrentTasks", 3);
        assertThat(body).containsEntry("currentActiveTasks", 1);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).containsEntry("taskId", "task-st-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void endpoint_returns200_degraded_whenNoQueryBean() {
        ResponseEntity<Map> response = rest.getForEntity(
                "http://localhost:" + port + "/v1/current_active_tasks", Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("currentActiveTasks");
        assertThat(body).containsKey("tasks");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ActiveTaskController activeTaskController(ObjectProvider<ActiveTaskQuery> provider) {
            return new ActiveTaskController(provider);
        }

        @Bean
        ActiveTaskQuery activeTaskQuery() {
            return () -> new ConcurrencyLoadSnapshot(3, 1, List.of(
                    new ActiveTaskInfo("task-st-1", "conv-st-1", "WORKING", System.currentTimeMillis())
            ));
        }
    }
}
