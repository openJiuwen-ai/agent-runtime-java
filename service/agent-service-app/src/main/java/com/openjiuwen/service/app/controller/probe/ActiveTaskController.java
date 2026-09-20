/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.probe;

import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller exposing the current active task load snapshot
 * (DFX-002).
 *
 * <p>When the ext module's {@code TaskQuotaTracker} bean is present, the
 * endpoint returns a real snapshot. When the bean is absent (ext module
 * not loaded), it returns a degraded response with zero active tasks.
 *
 * @since 0.1.2
 */
@ConditionalOnWebApplication
@ConditionalOnProperty(
        prefix = "openjiuwen.service.http", name = "enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class ActiveTaskController {
    private final ObjectProvider<ActiveTaskQuery> queryProvider;

    /**
     * Constructs the controller.
     *
     * @param queryProvider provider for {@link ActiveTaskQuery}, may be absent
     */
    public ActiveTaskController(ObjectProvider<ActiveTaskQuery> queryProvider) {
        this.queryProvider = queryProvider;
    }

    /**
     * Returns the current active task snapshot.
     *
     * @return the process snapshot, or a degraded response when no query bean is available
     */
    public ResponseEntity<?> getCurrentActTask() {
        return getCurrentActTask(null);
    }

    /**
     * Returns the current active task snapshot, optionally restricted to one agent.
     *
     * @param agentId optional hosted registration ID; absent selects the process snapshot
     * @return 200 with the snapshot, 400 for a blank ID, or 404 when the agent snapshot is unavailable
     */
    @GetMapping(AgentServicePaths.CURRENT_ACTIVE_TASKS)
    public ResponseEntity<?> getCurrentActTask(
            @RequestParam(name = "agentId", required = false) String agentId) {
        if (agentId != null && agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId must not be blank"));
        }
        ActiveTaskQuery query = queryProvider != null ? queryProvider.getIfAvailable() : null;
        if (query == null) {
            if (agentId != null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(degradedBody());
        }
        Optional<ConcurrencyLoadSnapshot> selected = agentId == null
                ? Optional.of(query.snapshot()) : query.snapshot(agentId);
        if (selected.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ConcurrencyLoadSnapshot snapshot = selected.get();
        return ResponseEntity.ok(snapshotBody(snapshot));
    }

    private static Map<String, Object> degradedBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxConcurrentTasks", -1);
        body.put("currentActiveTasks", 0);
        body.put("tasks", List.of());
        return body;
    }

    private static Map<String, Object> snapshotBody(ConcurrencyLoadSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxConcurrentTasks", snapshot.getMaxConcurrentTasks());
        body.put("currentActiveTasks", snapshot.getCurrentActiveTasks());
        body.put("tasks", snapshot.getTasks().stream()
                .map(ActiveTaskController::taskBody)
                .toList());
        return body;
    }

    private static Map<String, Object> taskBody(ActiveTaskInfo task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.getTaskId());
        body.put("conversationId", task.getConversationId());
        body.put("status", task.getStatus());
        body.put("startedAt", task.getStartedAt());
        return body;
    }
}
