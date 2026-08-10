/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.controller.a2a.ChunkMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Unit tests for terminal result selection in {@link A2ARemoteAgentClient}.
 */
class A2ARemoteAgentClientResultTest {
    private static final Gson GSON = new Gson();

    @Test
    void remoteStatesMapToStableResultCategories() {
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_COMPLETED)).isEqualTo("COMPLETED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_INPUT_REQUIRED))
                .isEqualTo("INPUT_REQUIRED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_REJECTED)).isEqualTo("REMOTE_REJECTED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_FAILED))
                .isEqualTo("REMOTE_BUSINESS_FAILURE");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_CANCELED)).isEqualTo("REMOTE_CANCELED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.UNRECOGNIZED)).isEqualTo("REMOTE_UNRECOGNIZED");
    }

    @Test
    void plainA2aArtifactIsReturnedWhenCompletedStatusArrives() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class,
                RemoteAgentCaller.EventObserver.class,
                boolean.class);
        statusMethod.setAccessible(true);
        for (String expected : List.of("balance=100", "{\"balance\":\"100\"}")) {
            CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
            Artifact artifact = new Artifact("artifact-plain", null, null, List.<Part<?>>of(new TextPart(expected)),
                    Map.of(), List.of());
            Task task = Task.builder().id("remote-task").contextId("remote-context")
                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();

            statusMethod.invoke(client,
                    new TaskStatusUpdateEvent("remote-task", new TaskStatus(TaskState.TASK_STATE_COMPLETED, null, null),
                            "remote-context", Map.of()),
                    task, result, mock(RemoteAgentCaller.EventObserver.class), false);

            assertThat(result.getNow(null).result()).isEqualTo(expected);
        }
    }

    @Test
    void workflowFinalArtifactIsUnwrappedWhenCompletedStatusArrives() throws Exception {
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class,
                RemoteAgentCaller.EventObserver.class,
                boolean.class);
        statusMethod.setAccessible(true);
        String expected = "Agent D expense review completed";
        Artifact artifact = new Artifact("artifact-workflow-final", null, null,
                List.<Part<?>>of(new TextPart(GSON.toJson(envelope("workflow_final", Map.of("response", expected))))),
                Map.of(), List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));

        statusMethod.invoke(
                client, new TaskStatusUpdateEvent("remote-task",
                        new TaskStatus(TaskState.TASK_STATE_COMPLETED, null, null), "remote-context", Map.of()),
                task, result, mock(RemoteAgentCaller.EventObserver.class), false);

        assertThat(result.getNow(null).result()).isEqualTo(expected);
    }

    @Test
    void structuredWorkflowFinalArtifactReturnsOnCompletedStatus() throws Exception {
        Map<String, Object> output = Map.of("auto_result", "Expense claim approved");
        List<Part<?>> parts = new ChunkMapper()
                .toParts(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope("workflow_final", Map.of("output", output))));
        Artifact textProgress = new Artifact("artifact-text-progress", null, null,
                List.<Part<?>>of(new TextPart("intermediate text")), Map.of(), List.of());
        Artifact traceProgress = new Artifact("artifact-trace-progress", null, null,
                List.<Part<?>>of(new DataPart(Map.of("type", "trace", "payload", Map.of("content", "reasoning")))),
                Map.of(), List.of());
        Artifact artifact = new Artifact("artifact-workflow-final", null, null, parts,
                Map.of("_agentcore_terminal", true), List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(textProgress, traceProgress, artifact)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class,
                RemoteAgentCaller.EventObserver.class,
                boolean.class);
        statusMethod.setAccessible(true);

        statusMethod.invoke(client, new TaskStatusUpdateEvent("remote-task", task.status(), "remote-context", Map.of()),
                task, result, mock(RemoteAgentCaller.EventObserver.class), false);

        assertThat(JsonParser.parseString(result.getNow(null).result())).isEqualTo(GSON.toJsonTree(output));
    }

    @Test
    void terminalTextArtifactExcludesEarlierPlainText() throws Exception {
        List<Part<?>> parts = new ChunkMapper()
                .toParts(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope("answer", Map.of("output", "final answer"))));
        Artifact progress = new Artifact("artifact-progress", null, null,
                List.<Part<?>>of(new TextPart("intermediate text")), Map.of(), List.of());
        Artifact answer = new Artifact("artifact-answer", null, null, parts, Map.of("_agentcore_terminal", true),
                List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(progress, answer)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class,
                RemoteAgentCaller.EventObserver.class,
                boolean.class);
        statusMethod.setAccessible(true);

        statusMethod.invoke(client, new TaskStatusUpdateEvent("remote-task", task.status(), "remote-context", Map.of()),
                task, result, mock(RemoteAgentCaller.EventObserver.class), false);

        assertThat(result.getNow(null).result()).isEqualTo("final answer");
    }

    @Test
    void answerArtifactDoesNotOverrideLaterFailedStatus() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        RemoteAgentCaller.EventObserver observer = mock(RemoteAgentCaller.EventObserver.class);
        Artifact answer = new Artifact("artifact-answer", null, null,
                List.<Part<?>>of(new TextPart(GSON.toJson(envelope("answer", Map.of("output", "premature"))))),
                Map.of(), List.of());
        Task workingTask = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).artifacts(List.of(answer)).build();
        Method eventMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleClientEvent",
                ClientEvent.class, CompletableFuture.class, RemoteAgentCaller.EventObserver.class,
                boolean.class, boolean.class);
        eventMethod.setAccessible(true);

        TaskArtifactUpdateEvent artifactUpdate = new TaskArtifactUpdateEvent("remote-task", answer, "remote-context",
                false, true, Map.of());
        eventMethod.invoke(client, new TaskUpdateEvent(workingTask, artifactUpdate), result, observer, false, true);
        assertThat(result).isNotDone();
        ArgumentCaptor<TaskArtifactUpdateEvent> updateCaptor = ArgumentCaptor.forClass(TaskArtifactUpdateEvent.class);
        verify(observer).onArtifact(updateCaptor.capture());
        assertThat(updateCaptor.getValue().artifact()).isSameAs(answer);

        Message failure = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.<Part<?>>of(new TextPart("declined"))).build();
        Task failedTask = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_FAILED, failure, null)).artifacts(List.of(answer)).build();
        TaskStatusUpdateEvent statusUpdate = new TaskStatusUpdateEvent("remote-task", failedTask.status(),
                "remote-context", Map.of());
        eventMethod.invoke(client, new TaskUpdateEvent(failedTask, statusUpdate), result, observer, false, true);

        assertThat(result.getNow(null).remoteState()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(result.getNow(null).result()).isEqualTo("declined");
    }

    @Test
    void nonStreamingTaskProjectsArtifactsBeforeStatusAndIgnoresLateEvents() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        RemoteAgentCaller.EventObserver observer = mock(RemoteAgentCaller.EventObserver.class);
        Artifact artifact = Artifact.builder().artifactId("artifact-final").parts(new TextPart("final")).build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();
        Method eventMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleClientEvent",
                ClientEvent.class, CompletableFuture.class, RemoteAgentCaller.EventObserver.class,
                boolean.class, boolean.class);
        eventMethod.setAccessible(true);

        eventMethod.invoke(client, new TaskEvent(task), result, observer, false, false);

        InOrder order = inOrder(observer);
        ArgumentCaptor<TaskArtifactUpdateEvent> artifactCaptor = ArgumentCaptor.forClass(
                TaskArtifactUpdateEvent.class);
        order.verify(observer).onArtifact(artifactCaptor.capture());
        order.verify(observer).onStatus(any(TaskStatusUpdateEvent.class));
        assertThat(artifactCaptor.getValue().artifact()).isSameAs(artifact);
        assertThat(artifactCaptor.getValue().append()).isFalse();
        assertThat(artifactCaptor.getValue().lastChunk()).isTrue();
        assertThat(result.getNow(null).result()).isEqualTo("final");

        clearInvocations(observer);
        TaskStatusUpdateEvent lateStatus = new TaskStatusUpdateEvent("remote-task",
                new TaskStatus(TaskState.TASK_STATE_WORKING), "remote-context", Map.of());
        eventMethod.invoke(client, new TaskUpdateEvent(task, lateStatus), result, observer, false, true);
        eventMethod.invoke(client, new TaskEvent(task), result, observer, false, false);
        verifyNoInteractions(observer);
    }

    @Test
    void nestedAgentEventsAreExcludedFromCompletedResult() throws Exception {
        Artifact delegation = Artifact.builder().artifactId("artifact-delegation").parts(new TextPart("delegate"))
                .metadata(Map.of("agentEvent", Map.of("type", "delegation",
                        "source", Map.of("agentId", "parent", "taskId", "remote-task"),
                        "target", Map.of("agentId", "child", "taskId", "child-task"))))
                .build();
        Artifact nestedOutput = Artifact.builder().artifactId("artifact-nested").parts(new TextPart("nested"))
                .metadata(Map.of("agentEvent", Map.of("type", "output",
                        "source", Map.of("agentId", "child", "taskId", "child-task"))))
                .build();
        Artifact artifact = Artifact.builder().artifactId("artifact-business").parts(new TextPart("business"))
                .build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(delegation, nestedOutput, artifact)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class,
                RemoteAgentCaller.EventObserver.class,
                boolean.class);
        statusMethod.setAccessible(true);

        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        statusMethod.invoke(client, new TaskStatusUpdateEvent("remote-task", task.status(), "remote-context", Map.of()),
                task, result, mock(RemoteAgentCaller.EventObserver.class), false);

        assertThat(result.getNow(null).result()).isEqualTo("business");
    }

    private static Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("index", 0);
        envelope.put("payload", payload);
        return envelope;
    }
}
