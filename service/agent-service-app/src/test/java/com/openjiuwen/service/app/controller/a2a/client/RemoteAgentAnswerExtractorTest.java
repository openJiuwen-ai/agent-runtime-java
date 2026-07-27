/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Unit tests for the answer discrimination and business-text extraction in
 * {@link RemoteAgentAnswerExtractor} and {@link A2ARemoteAgentClient}. The remote
 * caller keeps the AgentCore stream envelope in the forwarded stream (uniform
 * format) and unwraps terminal {@code answer} and {@code workflow_final} envelopes
 * into the tool result fed back to our LLM.
 */
class RemoteAgentAnswerExtractorTest {
    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("answer envelope is unwrapped to its payload business text")
    void answerEnvelopeUnwrapped() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output", "2");
        payload.put("result_type", "answer");
        String raw = GSON.toJson(envelope("answer", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).contains("2");
    }

    @Test
    @DisplayName("workflow_final envelope is unwrapped to its response business text")
    void workflowFinalEnvelopeUnwrapped() {
        String raw = GSON.toJson(envelope("workflow_final", Map.of("response", "expense approved")));

        assertThat(A2ARemoteAgentClient.answerText(raw)).contains("expense approved");
    }

    @Test
    @DisplayName("non-answer chunk is left enveloped (forwarded to the caller's stream verbatim)")
    void intermediateChunkNotAnswer() {
        // This llm_output delta even carries text "2", but it must NOT be treated as
        // the answer.
        Map<String, Object> payload = Map.of("content", "2");
        String raw = GSON.toJson(envelope("llm_output", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).isEmpty();
    }

    @Test
    @DisplayName("answer envelope without a text field falls back to the raw envelope text")
    void answerWithoutTextFallsBackToRaw() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", "abc");
        String raw = GSON.toJson(envelope("answer", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).contains(raw);
    }

    @Test
    @DisplayName("plain (non-JSON) text is not an answer envelope")
    void plainTextNotAnswer() {
        assertThat(RemoteAgentAnswerExtractor.extractAnswer("hello")).isEmpty();
    }

    @Test
    @DisplayName("extractBusinessText prefers payload text keys, then top level")
    void extractBusinessTextVariants() {
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText("2")).contains("2");
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText("   ")).isEmpty();
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(null)).isEmpty();
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(42)).isEmpty();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", "hel");
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(envelope("chunk", payload))).contains("hel");
    }

    @Test
    void remoteStatesMapToStableResultCategories() {
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_COMPLETED)).isEqualTo("COMPLETED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_INPUT_REQUIRED))
                .isEqualTo("INPUT_REQUIRED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_REJECTED)).isEqualTo("REMOTE_REJECTED");
        assertThat(A2ARemoteAgentClient.resultCategory(TaskState.TASK_STATE_FAILED))
                .isEqualTo("REMOTE_BUSINESS_FAILURE");
    }

    @Test
    void plainA2aArtifactIsReturnedWhenCompletedStatusArrives() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class, java.util.function.Consumer.class);
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
                    task, result, (java.util.function.Consumer<String>) ignored -> {
                    });

            assertThat(result.getNow(null).result()).isEqualTo(expected);
        }
    }

    @Test
    void workflowFinalArtifactIsUnwrappedWhenCompletedStatusArrives() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class, java.util.function.Consumer.class);
        statusMethod.setAccessible(true);
        String expected = "Agent D expense review completed";
        Artifact artifact = new Artifact("artifact-workflow-final", null, null,
                List.<Part<?>>of(new TextPart(GSON.toJson(envelope("workflow_final", Map.of("response", expected))))),
                Map.of(), List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        statusMethod.invoke(
                client, new TaskStatusUpdateEvent("remote-task",
                        new TaskStatus(TaskState.TASK_STATE_COMPLETED, null, null), "remote-context", Map.of()),
                task, result, (java.util.function.Consumer<String>) ignored -> {
                });

        assertThat(result.getNow(null).result()).isEqualTo(expected);
    }

    @Test
    void answerArtifactDoesNotOverrideLaterFailedStatus() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        Artifact answer = new Artifact("artifact-answer", null, null,
                List.<Part<?>>of(new TextPart(GSON.toJson(envelope("answer", Map.of("output", "premature"))))),
                Map.of(), List.of());
        Method artifactMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeArtifact",
                TaskArtifactUpdateEvent.class, CompletableFuture.class, QueryStreamObserver.class);
        artifactMethod.setAccessible(true);

        artifactMethod.invoke(client,
                new TaskArtifactUpdateEvent("remote-task", answer, "remote-context", false, true, Map.of()), result,
                mock(QueryStreamObserver.class));
        assertThat(result).isNotDone();

        Message failure = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.<Part<?>>of(new TextPart("declined"))).build();
        Task failedTask = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_FAILED, failure, null)).artifacts(List.of(answer)).build();
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class, java.util.function.Consumer.class);
        statusMethod.setAccessible(true);
        statusMethod.invoke(client,
                new TaskStatusUpdateEvent("remote-task", failedTask.status(), "remote-context", Map.of()), failedTask,
                result, (java.util.function.Consumer<String>) ignored -> {
                });

        assertThat(result.getNow(null).remoteState()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(result.getNow(null).result()).isEqualTo("declined");
    }

    @Test
    void structuredArtifactIsForwardedAsStructuredQueryChunk() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        Map<String, Object> data = envelope("llm_output", Map.of("content", "working"));
        Artifact artifact = new Artifact("artifact-data", null, null, List.<Part<?>>of(new DataPart(data)), Map.of(),
                List.of());
        Method artifactMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeArtifact",
                TaskArtifactUpdateEvent.class, CompletableFuture.class, QueryStreamObserver.class);
        artifactMethod.setAccessible(true);
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        artifactMethod.invoke(client,
                new TaskArtifactUpdateEvent("remote-task", artifact, "remote-context", false, true, Map.of()), result,
                observer);

        ArgumentCaptor<QueryChunk> chunkCaptor = ArgumentCaptor.forClass(QueryChunk.class);
        verify(observer).onNext(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getData()).isEqualTo(data);
    }

    @Test
    void internalProjectionPartsAreExcludedFromCompletedResult() throws Exception {
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(mock(A2ARemoteAgentCardRegistry.class));
        Artifact artifact = new Artifact("artifact-business", null, null,
                List.<Part<?>>of(new TextPart("internal", Map.of("_remote_invocation", Map.of("toolCallId", "call-a"))),
                        new TextPart("business")),
                Map.of(), List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        Method statusMethod = A2ARemoteAgentClient.class.getDeclaredMethod("handleOutcomeStatus",
                TaskStatusUpdateEvent.class, Task.class, CompletableFuture.class, java.util.function.Consumer.class);
        statusMethod.setAccessible(true);

        statusMethod.invoke(client, new TaskStatusUpdateEvent("remote-task", task.status(), "remote-context", Map.of()),
                task, result, (java.util.function.Consumer<String>) ignored -> {
                });

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
