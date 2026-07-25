/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallback;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Verifies push callback recovery against orchestrator-owned remote batch shadows.
 */
class A2AEnabledServeOrchestratorCallbackRecoveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void completedCallbackTaskUpdatesMatchingShadowMemberAndMarksBatchReady() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        taskStore.save(shadowTask("parent-callback", "remote-task-1"), true);
        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(mock(AgentHandler.class),
            taskStore, mock(A2ARemoteAgentClient.class), mock(ActiveStreamRegistry.class), "test-agent", 16, 256, 30);

        boolean recovered = orchestrator.onAccepted(new A2aPushNotificationCallback("notif-1",
            completedTask("remote-task-1", "callback-result")));

        assertThat(recovered).isTrue();
        Task shadow = taskStore.get("shadow:test-agent:parent-callback");
        Map<String, Object> batch = (Map<String, Object>) shadow.metadata().get("_remote_batch");
        List<Map<String, Object>> members = (List<Map<String, Object>>) batch.get("members");
        assertThat(batch).containsEntry("state", "READY_TO_RESUME");
        assertThat(members).singleElement().satisfies(member -> assertThat(member)
            .containsEntry("state", "COMPLETED")
            .containsEntry("remoteTaskId", "remote-task-1")
            .containsEntry("resultCategory", "COMPLETED")
            .containsEntry("result", "callback-result"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void callbackTaskWithResultTextAndWorkingStateIsTreatedAsCompleted() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        taskStore.save(shadowTask("parent-working-state", "remote-task-working-state"), true);
        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(mock(AgentHandler.class),
            taskStore, mock(A2ARemoteAgentClient.class), mock(ActiveStreamRegistry.class), "test-agent", 16, 256, 30);

        boolean recovered = orchestrator.onAccepted(new A2aPushNotificationCallback("notif-working-state",
            resultTask("remote-task-working-state", TaskState.TASK_STATE_WORKING, "callback text with working state")));

        assertThat(recovered).isTrue();
        Task shadow = taskStore.get("shadow:test-agent:parent-working-state");
        Map<String, Object> batch = (Map<String, Object>) shadow.metadata().get("_remote_batch");
        List<Map<String, Object>> members = (List<Map<String, Object>>) batch.get("members");
        assertThat(batch).containsEntry("state", "READY_TO_RESUME");
        assertThat(members).singleElement().satisfies(member -> assertThat(member)
            .containsEntry("state", "COMPLETED")
            .containsEntry("resultCategory", "COMPLETED")
            .containsEntry("result", "callback text with working state"));
    }

    private static Task shadowTask(String parentTaskId, String remoteTaskId) {
        return Task.builder()
            .id("shadow:test-agent:" + parentTaskId)
            .contextId("conversation-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .metadata(Map.of("_remote_batch", Map.of(
                "batchId", "batch-1",
                "parentTaskId", parentTaskId,
                "state", "WAITING_INPUT",
                "members", List.of(Map.of(
                    "index", 0,
                    "toolCallId", "call-a",
                    "toolName", "tool-a",
                    "agentName", "agent-a",
                    "state", "INPUT_REQUIRED",
                    "projectionSeq", 1,
                    "remoteTaskId", remoteTaskId,
                    "resultCategory", "INPUT_REQUIRED",
                    "inputPrompt", "waiting")))))
            .build();
    }

    private static Task completedTask(String taskId, String result) {
        return resultTask(taskId, TaskState.TASK_STATE_COMPLETED, result);
    }

    private static Task resultTask(String taskId, TaskState state, String result) {
        return Task.builder()
            .id(taskId)
            .contextId("remote-context-1")
            .status(new TaskStatus(state))
            .artifacts(List.of(Artifact.builder().artifactId("answer").parts(new TextPart(result)).build()))
            .build();
    }
}
