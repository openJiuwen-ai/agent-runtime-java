/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ActiveTaskController} (DFX-002 U-25~U-27).
 *
 * @since 0.1.2
 */
class ActiveTaskControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsSnapshot_whenQueryProvided() {
        ActiveTaskQuery query = mock(ActiveTaskQuery.class);
        when(query.snapshot()).thenReturn(new ConcurrencyLoadSnapshot(5, 2, List.of(
                new ActiveTaskInfo("task-1", "conv-1", "WORKING", 1000L),
                new ActiveTaskInfo("task-2", "conv-2", "WORKING", 2000L)
        )));
        ObjectProvider<ActiveTaskQuery> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(query);
        ActiveTaskController controller = new ActiveTaskController(provider);

        ResponseEntity<?> response = controller.getCurrentActTask();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("maxConcurrentTasks", 5);
        assertThat(body).containsEntry("currentActiveTasks", 2);
        assertThat((List<Map<String, Object>>) body.get("tasks")).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmpty200_whenQueryNull() {
        ObjectProvider<ActiveTaskQuery> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ActiveTaskController controller = new ActiveTaskController(provider);

        ResponseEntity<?> response = controller.getCurrentActTask();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("currentActiveTasks", 0);
        assertThat((List<?>) body.get("tasks")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshot_jsonStructure() {
        ActiveTaskQuery query = mock(ActiveTaskQuery.class);
        when(query.snapshot()).thenReturn(new ConcurrencyLoadSnapshot(3, 1, List.of(
                new ActiveTaskInfo("task-99", "conv-99", "WORKING", 1234567890L)
        )));
        ObjectProvider<ActiveTaskQuery> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(query);
        ActiveTaskController controller = new ActiveTaskController(provider);

        ResponseEntity<?> response = controller.getCurrentActTask();

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
        assertThat(tasks).hasSize(1);
        Map<String, Object> task = tasks.get(0);
        assertThat(task).containsEntry("taskId", "task-99");
        assertThat(task).containsEntry("conversationId", "conv-99");
        assertThat(task).containsEntry("status", "WORKING");
        assertThat(task).containsEntry("startedAt", 1234567890L);
    }
}
