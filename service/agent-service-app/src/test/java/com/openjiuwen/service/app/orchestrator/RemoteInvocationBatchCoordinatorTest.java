/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller.EventObserver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies bounded parallel remote invocation, output forwarding, persistence, and targeted resume behavior.
 *
 * @since 0.1.0
 */
class RemoteInvocationBatchCoordinatorTest {
    private static final int REMOTE_OUTPUT_COUNT = 256;

    @Test
    void concurrentCompletionKeepsOriginalToolCallOrder() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 3);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-1", "call-a", "call-b", "call-c"), request("parent-1", Map.of()),
            mock(QueryStreamObserver.class));

        verify(client, times(3)).callOutcome(any(), any());
        outcomes.get("call-c").complete(completed("remote-c", "result-c"));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(completed("remote-b", "result-b"));

        RemoteInvocationBatchCoordinator.BatchResolution resolution = result.join();
        assertThat(resolution.isReadyToResume()).isTrue();
        assertThat(resolution.results().keySet()).containsExactly("call-a", "call-b", "call-c");
        assertThat(resolution.results().values()).containsExactly("result-a", "result-b", "result-c");
    }

    @Test
    void concurrentRemoteOutputsKeepSourceMetadataWithoutStateProjections() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        AtomicReference<EventObserver> outputObserver = new AtomicReference<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            outputObserver.set(invocation.getArgument(1));
            return outcome;
        });
        List<QueryChunk> outputs = Collections.synchronizedList(new ArrayList<>());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doAnswer(invocation -> {
            outputs.add(invocation.getArgument(0));
            return null;
        }).when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-progress-order", "call-a"), request("parent-progress-order", Map.of()), observer);
        assertThat(result.isDone()).isFalse();
        emitConcurrentOutputs(outputObserver.get());
        List<QueryChunk> emittedBeforeCompletion = new ArrayList<>(outputs);
        outcome.complete(completed("remote-a", "result-a"));
        result.join();

        assertRemoteOutputs(emittedBeforeCompletion);
        assertThat(outputs).hasSize(REMOTE_OUTPUT_COUNT + 1);
    }

    @Test
    void statusProjectionPreservesEventOrderAndTerminalStateIsSticky() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        AtomicReference<EventObserver> remoteObserver = new AtomicReference<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            remoteObserver.set(invocation.getArgument(1));
            return outcome;
        });
        List<QueryChunk> outputs = new ArrayList<>();
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doAnswer(invocation -> {
            outputs.add(invocation.getArgument(0));
            return null;
        }).when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
                batch("batch-status", "call-a"), request("parent-status", Map.of()), observer);

        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_WORKING, "searching"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_WORKING, "searching"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_WORKING, "generating"));
        remoteObserver.get().onArtifact(remoteArtifact("remote-a", "progress"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_COMPLETED, "done"));
        remoteObserver.get().onArtifact(remoteArtifact("remote-a", "late output"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_WORKING, "late"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_FAILED, "conflict"));
        outcome.complete(completed("remote-a", "result-a"));
        result.join();

        List<TaskArtifactUpdateEvent> statuses = outputs.stream().map(QueryChunk::getData)
                .map(TaskArtifactUpdateEvent.class::cast)
                .filter(update -> "status".equals(agentEvent(update).get("type")))
                .toList();
        assertThat(statuses).hasSize(3);
        assertThat(statuses).extracting(update -> update.artifact().artifactId()).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).startsWith("status:agent-call-a:remote-a:"));
        assertThat(statuses).extracting(update -> String.valueOf(agentEvent(update).get("state")))
                .containsExactly("working", "working", "completed");
        assertThat(statuses).extracting(RemoteInvocationBatchCoordinatorTest::firstText)
                .containsExactly("searching", "generating", "done");
        assertThat(outputs).extracting(QueryChunk::getData).map(TaskArtifactUpdateEvent.class::cast)
                .extracting(update -> String.valueOf(agentEvent(update).get("type")))
                .containsExactly("delegation", "status", "status", "output", "status");
        assertThat(outputs).first().satisfies(chunk -> {
            TaskArtifactUpdateEvent delegation = (TaskArtifactUpdateEvent) chunk.getData();
            assertThat(agentEvent(delegation).get("toolCallId")).isEqualTo("call-a");
        });
    }

    @Test
    void eventsAfterBatchResolutionAreIgnored() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        AtomicReference<EventObserver> remoteObserver = new AtomicReference<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            remoteObserver.set(invocation.getArgument(1));
            return outcome;
        });
        List<QueryChunk> outputs = new ArrayList<>();
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doAnswer(invocation -> outputs.add(invocation.getArgument(0))).when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
                batch("batch-late-event", "call-a"), request("parent-late-event", Map.of()), observer);

        outcome.complete(completed("remote-a", "result-a"));
        result.join();
        remoteObserver.get().onArtifact(remoteArtifact("remote-a", "late output"));
        remoteObserver.get().onStatus(status("remote-a", TaskState.TASK_STATE_WORKING, "late status"));

        assertThat(outputs).isEmpty();
    }

    @Test
    void nestedSourceAndArtifactIdentitySurviveOpaqueReparenting() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        AtomicReference<EventObserver> remoteObserver = new AtomicReference<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            remoteObserver.set(invocation.getArgument(1));
            return outcome;
        });
        List<QueryChunk> outputs = new ArrayList<>();
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doAnswer(invocation -> {
            outputs.add(invocation.getArgument(0));
            return null;
        }).when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
                batch("batch-nested", "call-a"), request("parent-nested", Map.of()), observer);
        assertThat(result).isNotDone();
        Map<String, Object> metadata = new LinkedHashMap<>(
                Map.of("business", "kept", "_agentcore_terminal", true));
        metadata.put("agentEvent", outputEvent("D", "task-d"));
        Artifact first = Artifact.builder().artifactId("artifact-d").parts(new TextPart("D-1"))
                .metadata(metadata).build();
        Artifact second = Artifact.builder().artifactId("artifact-d").parts(new TextPart("D-2"))
                .metadata(metadata).build();
        remoteObserver.get().onArtifact(
                new TaskArtifactUpdateEvent("remote-a", first, "remote-context", false, false, Map.of()));
        remoteObserver.get().onArtifact(
                new TaskArtifactUpdateEvent("remote-a", second, "remote-context", true, true, Map.of()));
        outcome.complete(completed("remote-a", "result-a"));
        result.join();

        List<TaskArtifactUpdateEvent> nested = outputs.stream().map(QueryChunk::getData)
                .map(TaskArtifactUpdateEvent.class::cast)
                .filter(update -> "D".equals(agentSource(update).get("agentId")))
                .toList();
        assertThat(nested).hasSize(2);
        assertThat(nested).allSatisfy(update -> assertThat(update.artifact().artifactId()).isEqualTo("artifact-d"));
        assertThat(nested).allSatisfy(update -> {
            assertThat(update.artifact().metadata()).containsEntry("business", "kept")
                    .doesNotContainKey("_agentcore_terminal");
            assertThat(agentSource(update).get("agentId")).isEqualTo("D");
            assertThat(agentSource(update).get("taskId")).isEqualTo("task-d");
        });
    }

    @Test
    void batchDoesNotResolveBeforeQueuedRemoteOutputsAreDelivered() throws Exception {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = new LinkedHashMap<>();
        Map<String, EventObserver> outputObservers = new LinkedHashMap<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            String id = call.message().substring("message-".length());
            outputObservers.put(id, invocation.getArgument(1));
            return outcomes.computeIfAbsent(id, ignored -> new CompletableFuture<>());
        });
        CountDownLatch outputEntered = new CountDownLatch(1);
        CountDownLatch releaseOutput = new CountDownLatch(1);
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doAnswer(invocation -> {
            QueryChunk chunk = invocation.getArgument(0);
            if (chunk.getData() instanceof TaskArtifactUpdateEvent update
                    && "blocked-output".equals(firstText(update))) {
                outputEntered.countDown();
                releaseOutput.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 2);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-output-barrier", "call-a", "call-b"),
            request("parent-output-barrier", Map.of()), observer);
        assertThat(result).isNotDone();
        CompletableFuture<Void> blockedCallback = CompletableFuture.runAsync(() -> outputObservers.get("call-a")
                .onArtifact(remoteArtifact("remote-a", "blocked-output")));
        assertThat(outputEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(blockedCallback).isNotDone();

        CountDownLatch remoteCompletionStarted = new CountDownLatch(1);
        CompletableFuture<Void> remoteCompletions = CompletableFuture.runAsync(() -> {
            remoteCompletionStarted.countDown();
            outcomes.get("call-a").complete(completed("remote-a", "result-a"));
            outcomes.get("call-b").complete(completed("remote-b", "result-b"));
        });
        assertThat(remoteCompletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> result.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        } finally {
            releaseOutput.countDown();
        }

        remoteCompletions.get(5, TimeUnit.SECONDS);
        blockedCallback.get(5, TimeUnit.SECONDS);
        assertThat(result.get(5, TimeUnit.SECONDS).isReadyToResume()).isTrue();
    }

    @Test
    void maxConcurrencyOneStartsQueuedMembersInFifoOrder() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-fifo", "call-a", "call-b", "call-c"), request("parent-fifo", Map.of()),
            mock(QueryStreamObserver.class));

        ArgumentCaptor<RemoteCall> calls = ArgumentCaptor.forClass(RemoteCall.class);
        verify(client).callOutcome(calls.capture(), any());
        assertThat(calls.getValue().message()).isEqualTo("message-call-a");

        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        verify(client, times(2)).callOutcome(calls.capture(), any());
        assertThat(calls.getAllValues().get(calls.getAllValues().size() - 1).message()).isEqualTo("message-call-b");

        outcomes.get("call-b").complete(completed("remote-b", "result-b"));
        verify(client, times(3)).callOutcome(calls.capture(), any());
        assertThat(calls.getAllValues().get(calls.getAllValues().size() - 1).message()).isEqualTo("message-call-c");
        outcomes.get("call-c").complete(completed("remote-c", "result-c"));
        assertThat(result.join().isReadyToResume()).isTrue();
    }

    @Test
    void queuedMemberTimesOutWithoutWaitingForRunningMemberToFinish() throws Exception {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(new InMemoryTaskStore(),
            client, "test-agent", 1, 10, 1);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-queue-timeout", "call-a", "call-b"), request("parent-queue-timeout", Map.of()),
            mock(QueryStreamObserver.class));

        TimeUnit.MILLISECONDS.sleep(1500);
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));

        verify(client).callOutcome(any(), any());
        assertThat(result.join().results().get("call-b").toString()).contains("REMOTE_OVERLOADED");
    }

    @Test
    void queueCapacityZeroFailsExcessMemberWithoutRemoteCall() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(new InMemoryTaskStore(),
            client, "test-agent", 1, 0, 30);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-queue-full", "call-a", "call-b"), request("parent-queue-full", Map.of()),
            mock(QueryStreamObserver.class));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));

        verify(client).callOutcome(any(), any());
        assertThat(result.join().results().get("call-b").toString()).contains("REMOTE_OVERLOADED");
    }

    @Test
    void secondActiveBatchForSameParentIsRejected() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 3);
        ServeRequest request = request("parent-duplicate-active", Map.of());
        Map<String, Object> interrupt = batch("batch-duplicate-active", "call-a", "call-b", "call-c");

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> first =
            coordinator.execute(interrupt, request, mock(QueryStreamObserver.class));
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> second =
            coordinator.execute(interrupt, request, mock(QueryStreamObserver.class));

        verify(client, times(3)).callOutcome(any(), any());
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(completed("remote-b", "result-b"));
        outcomes.get("call-c").complete(completed("remote-c", "result-c"));
        first.join();
        assertThatThrownBy(second::join)
            .hasRootCauseMessage("REMOTE_BATCH_ALREADY_ACTIVE: parent-duplicate-active");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialInputRequiredPersistsCompletedResultsAndAllPendingRoutes() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 3, 10, 30);
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-wait", "call-a", "call-b", "call-c"), request("parent-wait", Map.of()),
            observer);
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(inputRequired("remote-b", "input-b"));
        outcomes.get("call-c").complete(inputRequired("remote-c", "input-c"));

        RemoteInvocationBatchCoordinator.BatchResolution resolution = result.join();
        assertThat(resolution.isReadyToResume()).isFalse();
        assertThat((List<Map<String, Object>>) resolution.interrupt().get("items"))
            .extracting(item -> item.get("toolCallId"))
            .containsExactly("call-b", "call-c");
        verify(observer, never()).onNext(any());

        Task shadow = store.get("shadow:test-agent:parent-wait");
        assertThat(shadow).isNotNull();
        Map<String, Object> remoteBatch = (Map<String, Object>) shadow.metadata().get("_remote_batch");
        List<Map<String, Object>> members = (List<Map<String, Object>>) remoteBatch.get("members");
        assertThat(members.get(0)).containsEntry("state", "COMPLETED").containsEntry("result", "result-a");
        assertThat(members.get(1)).containsEntry("remoteTaskId", "remote-b");
        assertThat(members.get(2)).containsEntry("remoteTaskId", "remote-c");
    }

    @Test
    @SuppressWarnings("unchecked")
    void targetedResumeClearsWaitingStateAndAdvancesOnlySelectedMember() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 3, 10, 30);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> initial = coordinator.execute(
            batch("batch-resume", "call-a", "call-b", "call-c"), request("parent-resume", Map.of()),
            mock(QueryStreamObserver.class));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(inputRequired("remote-b", "input-b"));
        outcomes.get("call-c").complete(inputRequired("remote-c", "input-c"));
        initial.join();

        CompletableFuture<RemoteCallOutcome> resumedB = new CompletableFuture<>();
        doAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return "remote-b".equals(call.taskId()) ? resumedB : CompletableFuture.failedFuture(
                new AssertionError("Unexpected resume target: " + call.taskId()));
        }).when(client).callOutcome(any(), any());
        ServeRequest resumeRequest = request("parent-resume",
            Map.of("runtime.remoteToolInputs", Map.of("call-b", "answer-b")));

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> resumed =
            coordinator.resume(resumeRequest, observer);
        assertThat(resumed).isPresent();
        resumedB.complete(completed("remote-b", "result-b"));

        RemoteInvocationBatchCoordinator.BatchResolution resolution = resumed.orElseThrow().join();
        assertThat(resolution.isReadyToResume()).isFalse();
        assertThat(resolution.interrupt().toString()).contains("call-c").doesNotContain("call-b");
        verify(observer, never()).onNext(any());
        Task shadow = store.get("shadow:test-agent:parent-resume");
        Map<String, Object> snapshot = (Map<String, Object>) shadow.metadata().get("_remote_batch");
        List<Map<String, Object>> members = (List<Map<String, Object>>) snapshot.get("members");
        assertThat(members).filteredOn(member -> "call-b".equals(member.get("toolCallId")))
            .allSatisfy(member -> assertThat(member).doesNotContainKey("inputPrompt"));
    }

    @Test
    void targetedResumeRejectsMismatchedParentTask() {
        WaitingBatch fixture = waitingBatch("parent-correct");
        ServeRequest resumeRequest = request("parent-wrong",
            Map.of("runtime.remoteToolInputs", Map.of("call-b", "answer-b")));

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> resumed =
            fixture.coordinator.resume(resumeRequest, mock(QueryStreamObserver.class));

        assertThat(resumed).isPresent();
        assertThatThrownBy(() -> resumed.orElseThrow().join())
            .hasRootCauseMessage("REMOTE_BATCH_PARENT_MISMATCH");
        verify(fixture.client, times(3)).callOutcome(any(), any());
    }

    @Test
    void targetedResumeDistinguishesUnknownMemberFromMemberStateConflict() {
        WaitingBatch fixture = waitingBatch("parent-target-validation");

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> unknown =
            fixture.coordinator.resume(request("parent-target-validation",
                Map.of("runtime.remoteToolInputs", Map.of("call-unknown", "answer"))),
                mock(QueryStreamObserver.class));
        assertThatThrownBy(() -> unknown.orElseThrow().join())
            .hasRootCauseMessage("REMOTE_TOOL_INPUT_TARGET_UNKNOWN: call-unknown");

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> completed =
            fixture.coordinator.resume(request("parent-target-validation",
                Map.of("runtime.remoteToolInputs", Map.of("call-a", "answer"))),
                mock(QueryStreamObserver.class));
        assertThatThrownBy(() -> completed.orElseThrow().join())
            .hasRootCauseMessage("REMOTE_TOOL_INPUT_STATE_CONFLICT: call-a");
    }

    @Test
    void multiplePendingMembersRequireTargetedInput() {
        WaitingBatch fixture = waitingBatch("parent-target-required");

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> resumed =
            fixture.coordinator.resume(request("parent-target-required", Map.of()), mock(QueryStreamObserver.class));

        assertThatThrownBy(() -> resumed.orElseThrow().join())
            .hasRootCauseMessage("REMOTE_TOOL_INPUT_TARGET_REQUIRED");
    }

    @Test
    void remoteOutputFailureFailsBatchAfterStartingRemoteCall() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        AtomicReference<EventObserver> outputObserver = new AtomicReference<>();
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            outputObserver.set(invocation.getArgument(1));
            return outcome;
        });
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("output sink unavailable"))
            .when(observer).onNext(any());
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 3);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-output-failure", "call-a"), request("parent-output-failure", Map.of()), observer);
        outputObserver.get().onArtifact(remoteArtifact("remote-a", "remote output"));

        assertThatThrownBy(result::join)
            .hasCauseInstanceOf(RuntimeException.class)
            .hasRootCauseMessage("output sink unavailable");
        verify(client).callOutcome(any(), any());
    }

    @Test
    void rateLimitedRemoteFailureUsesStableResultCategory() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("HTTP 429 Too Many Requests")));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        RemoteInvocationBatchCoordinator.BatchResolution resolution = coordinator.execute(
            batch("batch-rate-limit", "call-a"), request("parent-rate-limit", Map.of()),
            mock(QueryStreamObserver.class)).join();

        assertThat(resolution.isReadyToResume()).isTrue();
        assertThat(resolution.results().get("call-a").toString()).contains("REMOTE_RATE_LIMITED");
    }

    @Test
    void rejectedLocalIoSubmissionUsesOverloadedResultCategory() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RejectedExecutionException("I/O executor saturated")));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        RemoteInvocationBatchCoordinator.BatchResolution resolution = coordinator.execute(
            batch("batch-io-overloaded", "call-a"), request("parent-io-overloaded", Map.of()),
            mock(QueryStreamObserver.class)).join();

        assertThat(resolution.results().get("call-a").toString()).contains("REMOTE_OVERLOADED");
    }

    @Test
    void malformedRemoteResponseUsesProtocolErrorCategory() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("Malformed JSON-RPC response from remote agent")));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        RemoteInvocationBatchCoordinator.BatchResolution resolution = coordinator.execute(
            batch("batch-protocol-error", "call-a"), request("parent-protocol-error", Map.of()),
            mock(QueryStreamObserver.class)).join();

        assertThat(resolution.results().get("call-a").toString()).contains("REMOTE_PROTOCOL_ERROR");
    }

    @Test
    void synchronousClientRuntimeFailureSettlesMember() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenThrow(new IllegalArgumentException("invalid remote client configuration"));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        RemoteInvocationBatchCoordinator.BatchResolution resolution = coordinator.execute(
            batch("ignored-batch-id", "call-a"), request("parent-sync-client-failure", Map.of()),
            mock(QueryStreamObserver.class)).join();

        assertThat(resolution.results().get("call-a").toString()).contains("REMOTE_UNAVAILABLE");
    }

    @Test
    void coordinatorGeneratesBatchId() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(completed("remote-a", "result-a")));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);

        RemoteInvocationBatchCoordinator.BatchResolution resolution = coordinator.execute(
            batch("caller-controlled-batch", "call-a"), request("parent-generated-batch", Map.of()),
            mock(QueryStreamObserver.class)).join();

        assertThat(resolution.batchId()).isNotBlank().isNotEqualTo("caller-controlled-batch");
    }

    @Test
    @SuppressWarnings("unchecked")
    void timedOutMemberReturnsStableStructuredResult() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        when(client.callOutcome(any(), any())).thenReturn(outcome);
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(
            new InMemoryTaskStore(), client, "test-agent", 1, 10, 30);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-timeout", "call-a"), request("parent-timeout", Map.of()), mock(QueryStreamObserver.class));

        outcome.completeExceptionally(new TimeoutException("remote timeout"));
        Map<String, Object> failure = (Map<String, Object>) result.join().results().get("call-a");

        assertThat(failure).containsEntry("ok", false)
            .containsEntry("code", "REMOTE_TIMEOUT")
            .containsEntry("remoteAgentId", "agent-call-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyResultsRemainPersistedWhileCoreResumeIsInFlight() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 1, 10, 30);
        ServeRequest request = request("parent-ready", Map.of());
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-ready", "call-a"), request, mock(QueryStreamObserver.class));

        outcomes.get("call-a").complete(completed("remote-a", "result-a"));

        assertThat(result.join().isReadyToResume()).isTrue();
        Task shadow = store.get("shadow:test-agent:parent-ready");
        assertThat(shadow).isNotNull();
        assertThat((Map<String, Object>) shadow.metadata().get("_remote_batch"))
            .containsEntry("state", "READY_TO_RESUME");
        assertThat(coordinator.claimCoreResume(request, result.join().batchId())).isTrue();
        assertThat(coordinator.claimCoreResume(request, result.join().batchId())).isFalse();
        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> retry =
            coordinator.resume(request, mock(QueryStreamObserver.class));
        assertThat(retry).isPresent();
        assertThat(retry.orElseThrow().join().isReadyToResume()).isTrue();
        verify(client).callOutcome(any(), any());
    }

    @Test
    void staleCoreCompletionDoesNotDeleteNewerBatchShadow() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 1, 10, 30);
        store.save(Task.builder().id("shadow:test-agent:parent-stale-complete").contextId("conversation-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .metadata(Map.of("_remote_batch", Map.of(
                "batchId", "new-batch", "parentTaskId", "parent-stale-complete", "state", "WAITING_INPUT",
                "members", List.of())))
            .build(), true);
        ServeRequest stale = request("parent-stale-complete", Map.of(
            "runtime.remoteToolResults", Map.of("call-a", "result-a"),
            "runtime.remoteBatchId", "old-batch"));

        coordinator.completeResume(stale);

        assertThat(store.get("shadow:test-agent:parent-stale-complete")).isNotNull();
    }

    @Test
    void readySnapshotRejectsUnknownTargetedInput() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(
            new InMemoryTaskStore(), client, "test-agent", 3, 10, 30);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> initial = coordinator.execute(
            batch("batch-ready-stale", "call-a", "call-b", "call-c"),
            request("parent-ready-stale", Map.of()), mock(QueryStreamObserver.class));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(completed("remote-b", "result-b"));
        outcomes.get("call-c").complete(completed("remote-c", "result-c"));
        assertThat(initial.join().isReadyToResume()).isTrue();
        ServeRequest stale = request("parent-ready-stale",
            Map.of("runtime.remoteToolInputs", Map.of("call-unknown", "stale")));

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> retry =
            coordinator.resume(stale, mock(QueryStreamObserver.class));

        assertThat(retry).isPresent();
        assertThatThrownBy(() -> retry.orElseThrow().join())
            .hasRootCauseMessage("REMOTE_TOOL_INPUT_TARGET_UNKNOWN: call-unknown");
    }

    @Test
    void oneFailedMemberDoesNotAffectSuccessfulMembers() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 3);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-partial-failure", "call-a", "call-b", "call-c"),
            request("parent-partial-failure", Map.of()), mock(QueryStreamObserver.class));
        outcomes.get("call-b").complete(new RemoteCallOutcome("remote-b", TaskState.TASK_STATE_FAILED,
            "REMOTE_BUSINESS_FAILURE", "declined", null));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-c").complete(completed("remote-c", "result-c"));

        RemoteInvocationBatchCoordinator.BatchResolution resolution = result.join();
        assertThat(resolution.results().get("call-a")).isEqualTo("result-a");
        assertThat(resolution.results().get("call-b").toString()).contains("REMOTE_BUSINESS_FAILURE");
        assertThat(resolution.results().get("call-c")).isEqualTo("result-c");
    }

    @Test
    void terminalCallbackSchedulesContinuationWithParentIdentity() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        CompletableFuture<RemoteCallOutcome> outcome = new CompletableFuture<>();
        when(client.callOutcome(any(), any())).thenReturn(outcome);
        InMemoryTaskStore store = new InMemoryTaskStore();
        AtomicInteger continuations = new AtomicInteger();
        AtomicReference<ServeRequest> continuationRequest = new AtomicReference<>();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
                "test-agent", 1, 10, 30, request -> {
                    continuationRequest.set(request);
                    continuations.incrementAndGet();
                });
        coordinator.execute(batch("batch-callback", "call-a"), request("parent-callback", Map.of()),
                mock(QueryStreamObserver.class));
        outcome.complete(inputRequired("remote-a", "callback pending"));
        Task callbackTask = Task.builder().id("remote-a").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();

        assertThat(coordinator.recoverCallback(callbackTask)).isTrue();

        assertThat(continuations).hasValue(1);
        assertThat(continuationRequest.get().getMetadata())
                .containsEntry("runtime.parentTaskId", "parent-callback")
                .containsKey("runtime.remoteBatchId");
    }

    @Test
    void failedMemberResultSurvivesWaitingSnapshotAndTargetedResume() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 2, 10, 30);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> initial = coordinator.execute(
            batch("batch-failed-snapshot", "call-a", "call-b"), request("parent-failed-snapshot", Map.of()),
            mock(QueryStreamObserver.class));
        outcomes.get("call-a").complete(new RemoteCallOutcome("remote-a", TaskState.TASK_STATE_FAILED,
            "REMOTE_BUSINESS_FAILURE", "account service declined", null));
        outcomes.get("call-b").complete(inputRequired("remote-b", "input-b"));
        initial.join();

        CompletableFuture<RemoteCallOutcome> resumedB = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(resumedB).when(client).callOutcome(any(), any());
        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> resumed = coordinator.resume(
            request("parent-failed-snapshot", Map.of("runtime.remoteToolInputs", Map.of("call-b", "answer-b"))),
            mock(QueryStreamObserver.class));
        resumedB.complete(completed("remote-b", "result-b"));

        Object failedResult = resumed.orElseThrow().join().results().get("call-a");
        assertThat(failedResult.toString())
            .contains("REMOTE_BUSINESS_FAILURE")
            .contains("account service declined");
    }

    @Test
    void outboundMetadataOnlyKeepsNonControlFields() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(completed("remote-a", "result-a")));
        RemoteInvocationBatchCoordinator coordinator = coordinator(client, 1);
        ServeRequest request = request("parent-metadata", Map.of(
            "traceId", "trace-1",
            "_interrupt", Map.of("secret", "internal"),
            "runtime.remoteToolInputs", Map.of("call-a", "forged"),
            "runtime.remoteBatchId", "forged-batch",
            "runtime.remoteToolResults", Map.of("call-a", "forged")));

        coordinator.execute(batch("batch-metadata", "call-a"), request, mock(QueryStreamObserver.class)).join();

        ArgumentCaptor<RemoteCall> call = ArgumentCaptor.forClass(RemoteCall.class);
        verify(client).callOutcome(call.capture(), any());
        assertThat(call.getValue().metadata()).containsOnly(Map.entry("traceId", "trace-1"));
    }

    @Test
    void shadowStoreFailurePreventsCoreResume() {
        TaskStore store = mock(TaskStore.class);
        when(store.get(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("task store unavailable"))
            .when(store).save(any(), anyBoolean());
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        when(client.callOutcome(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(inputRequired("remote-a", "input-a")));
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 1, 10, 30);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> result = coordinator.execute(
            batch("batch-store-failure", "call-a"), request("parent-store-failure", Map.of()),
            mock(QueryStreamObserver.class));

        assertThatThrownBy(result::join).hasRootCauseMessage("task store unavailable");
    }

    @Test
    void multiMemberRemoteContextsAreIsolatedAndStableAcrossResume() {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        assertThat(outcomes).containsKeys("call-a", "call-b", "call-c");
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 3, 10, 30);

        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> initial = coordinator.execute(
            batch("batch-context", "call-a", "call-b", "call-c"), request("parent-context", Map.of()),
            mock(QueryStreamObserver.class));
        assertThat(initial.isDone()).isFalse();
        Map<String, String> initialContexts = captureInitialContexts(client);
        assertInitialContexts(initialContexts);

        RemoteInvocationBatchMapper mapper = new RemoteInvocationBatchMapper();
        outcomes.get("call-a").complete(mapper.callbackOutcome(inputRequiredTask("remote-a", "input-a")));
        outcomes.get("call-b").complete(mapper.callbackOutcome(inputRequiredTask("remote-b", "input-b")));
        outcomes.get("call-c").complete(mapper.callbackOutcome(inputRequiredTask("remote-c", "input-c")));
        initial.join();

        Map<String, CompletableFuture<RemoteCallOutcome>> resumedOutcomes = new LinkedHashMap<>();
        for (String id : List.of("remote-a", "remote-b", "remote-c")) {
            resumedOutcomes.put(id, new CompletableFuture<>());
        }
        org.mockito.Mockito.clearInvocations(client);
        doAnswer(invocation -> resumedOutcomes.get(invocation.<RemoteCall>getArgument(0).taskId()))
            .when(client).callOutcome(any(), any());
        ServeRequest resumeRequest = request("parent-context", Map.of("runtime.remoteToolInputs", Map.of(
            "call-a", "answer-a", "call-b", "answer-b", "call-c", "answer-c")));

        Optional<CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> resumed =
            coordinator.resume(resumeRequest, mock(QueryStreamObserver.class));
        assertThat(resumed).isPresent();

        assertResumedContexts(client, initialContexts);

        resumedOutcomes.get("remote-a").complete(mapper.callbackOutcome(completedTask("remote-a", "result-a")));
        resumedOutcomes.get("remote-b").complete(mapper.callbackOutcome(completedTask("remote-b", "result-b")));
        resumedOutcomes.get("remote-c").complete(mapper.callbackOutcome(completedTask("remote-c", "result-c")));
        RemoteInvocationBatchCoordinator.BatchResolution resolution = resumed.orElseThrow().join();
        assertThat(resolution.isReadyToResume()).isTrue();
        assertThat(resolution.results()).containsExactly(
                Map.entry("call-a", "result-a"),
                Map.entry("call-b", "result-b"),
                Map.entry("call-c", "result-c"));
    }

    private static Map<String, String> captureInitialContexts(A2ARemoteAgentClient client) {
        ArgumentCaptor<RemoteCall> calls = ArgumentCaptor.forClass(RemoteCall.class);
        verify(client, times(3)).callOutcome(calls.capture(), any());
        Map<String, String> contexts = new LinkedHashMap<>();
        calls.getAllValues().forEach(call -> contexts.put(call.message(), call.contextId()));
        return contexts;
    }

    private static void assertInitialContexts(Map<String, String> contexts) {
        assertThat(contexts.values()).doesNotHaveDuplicates();
        assertThat(contexts.get("message-call-a")).startsWith("conversation-1_").endsWith("_call-a")
                .doesNotContain(":");
        assertThat(contexts.get("message-call-b")).startsWith("conversation-1_").endsWith("_call-b")
                .doesNotContain(":");
        assertThat(contexts.get("message-call-c")).startsWith("conversation-1_").endsWith("_call-c")
                .doesNotContain(":");
    }

    private static void assertResumedContexts(A2ARemoteAgentClient client,
            Map<String, String> initialContexts) {
        ArgumentCaptor<RemoteCall> calls = ArgumentCaptor.forClass(RemoteCall.class);
        verify(client, times(3)).callOutcome(calls.capture(), any());
        Map<String, String> expected = Map.of(
                "remote-a", initialContexts.get("message-call-a"),
                "remote-b", initialContexts.get("message-call-b"),
                "remote-c", initialContexts.get("message-call-c"));
        assertThat(calls.getAllValues())
                .allSatisfy(call -> assertThat(call.contextId()).isEqualTo(expected.get(call.taskId())));
    }

    private static RemoteInvocationBatchCoordinator coordinator(A2ARemoteAgentClient client, int maxConcurrency) {
        return new RemoteInvocationBatchCoordinator(new InMemoryTaskStore(), client, "test-agent", maxConcurrency,
            10, 30);
    }

    private static void emitConcurrentOutputs(EventObserver observer) {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> callbacks = new ArrayList<>();
        for (int index = 0; index < REMOTE_OUTPUT_COUNT; index++) {
            callbacks.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                observer.onArtifact(remoteArtifact("remote-a", "progress"));
            }));
        }
        start.countDown();
        CompletableFuture.allOf(callbacks.toArray(CompletableFuture[]::new)).join();
    }

    private static void assertRemoteOutputs(List<QueryChunk> outputs) {
        List<QueryChunk> businessOutputs = outputs.stream()
                .filter(chunk -> chunk.getData() instanceof TaskArtifactUpdateEvent update
                        && "output".equals(agentEvent(update).get("type")))
                .toList();
        assertThat(businessOutputs).hasSize(REMOTE_OUTPUT_COUNT);
        assertThat(businessOutputs).allSatisfy(chunk -> {
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT);
            assertThat(chunk.getData()).isInstanceOfSatisfying(TaskArtifactUpdateEvent.class, update -> {
                assertThat(firstText(update)).isEqualTo("progress");
                assertThat(agentSource(update).get("agentId")).isEqualTo("agent-call-a");
                assertThat(agentSource(update).get("taskId")).isEqualTo("remote-a");
            });
        });
    }

    private static String firstText(TaskArtifactUpdateEvent update) {
        Object part = update.artifact().parts().get(0);
        if (part instanceof TextPart textPart) {
            return textPart.text();
        }
        throw new AssertionError("Expected first Artifact part to be text");
    }

    private static Map<?, ?> agentEvent(TaskArtifactUpdateEvent update) {
        return (Map<?, ?>) update.artifact().metadata().get(RemoteAgentCaller.AGENT_EVENT_METADATA);
    }

    private static Map<?, ?> agentSource(TaskArtifactUpdateEvent update) {
        return (Map<?, ?>) agentEvent(update).get("source");
    }

    private static Map<String, Object> outputEvent(String agentId, String taskId) {
        return Map.of("type", "output", "source", Map.of("agentId", agentId, "taskId", taskId));
    }

    private static TaskArtifactUpdateEvent remoteArtifact(String taskId, String text) {
        Artifact artifact = Artifact.builder().artifactId("artifact-progress").parts(new TextPart(text)).build();
        return new TaskArtifactUpdateEvent(taskId, artifact, "remote-context", true, false, Map.of());
    }

    private static TaskStatusUpdateEvent status(String taskId, TaskState state, String text) {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart(text)).build();
        TaskStatus status = new TaskStatus(state, message, null);
        return new TaskStatusUpdateEvent(taskId, status, "remote-context", Map.of());
    }

    private static WaitingBatch waitingBatch(String parentTaskId) {
        A2ARemoteAgentClient client = mock(A2ARemoteAgentClient.class);
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = outcomeFutures(client);
        InMemoryTaskStore store = new InMemoryTaskStore();
        RemoteInvocationBatchCoordinator coordinator = new RemoteInvocationBatchCoordinator(store, client,
            "test-agent", 3, 10, 30);
        CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> initial = coordinator.execute(
            batch("batch-" + parentTaskId, "call-a", "call-b", "call-c"), request(parentTaskId, Map.of()),
            mock(QueryStreamObserver.class));
        outcomes.get("call-a").complete(completed("remote-a", "result-a"));
        outcomes.get("call-b").complete(inputRequired("remote-b", "input-b"));
        outcomes.get("call-c").complete(inputRequired("remote-c", "input-c"));
        initial.join();
        return new WaitingBatch(coordinator, client);
    }

    private record WaitingBatch(RemoteInvocationBatchCoordinator coordinator, A2ARemoteAgentClient client) {
    }

    private static Map<String, CompletableFuture<RemoteCallOutcome>> outcomeFutures(A2ARemoteAgentClient client) {
        Map<String, CompletableFuture<RemoteCallOutcome>> outcomes = new LinkedHashMap<>();
        for (String id : List.of("call-a", "call-b", "call-c")) {
            outcomes.put(id, new CompletableFuture<>());
        }
        when(client.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            String id = call.message().substring("message-".length());
            return outcomes.get(id);
        });
        return outcomes;
    }

    private static ServeRequest request(String parentTaskId, Map<String, Object> extraMetadata) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conversation-1");
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put("runtime.parentTaskId", parentTaskId);
        request.setMetadata(metadata);
        return request;
    }

    private static Map<String, Object> batch(String batchId, String... toolCallIds) {
        List<Map<String, Object>> items = java.util.Arrays.stream(toolCallIds)
            .map(id -> Map.<String, Object>of(
                "index", List.of(toolCallIds).indexOf(id),
                "toolCallId", id,
                "toolName", "tool-" + id,
                "message", "message-" + id,
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "agent-" + id)))
            .toList();
        return Map.of("batchId", batchId, "items", items);
    }

    private static RemoteCallOutcome completed(String taskId, String result) {
        return new RemoteCallOutcome(taskId, TaskState.TASK_STATE_COMPLETED, "COMPLETED", result, null);
    }

    private static RemoteCallOutcome inputRequired(String taskId, String prompt) {
        return new RemoteCallOutcome(taskId, TaskState.TASK_STATE_INPUT_REQUIRED, "INPUT_REQUIRED", null, prompt);
    }

    private static Task inputRequiredTask(String taskId, String prompt) {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart(prompt)).build();
        return Task.builder().id(taskId).contextId("context-" + taskId)
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, message, null)).artifacts(List.of())
                .build();
    }

    private static Task completedTask(String taskId, String result) {
        Artifact nested = Artifact.builder().artifactId("nested-" + taskId).parts(new TextPart("nested-progress"))
                .metadata(Map.of(RemoteAgentCaller.AGENT_EVENT_METADATA,
                        Map.of("type", "output", "source", Map.of("agentId", "nested", "taskId", "nested-task"))))
                .build();
        Artifact terminal = Artifact.builder().artifactId("terminal-" + taskId).parts(new TextPart(result))
                .metadata(Map.of("_agentcore_terminal", true)).build();
        return Task.builder().id(taskId).contextId("context-" + taskId)
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(nested, terminal)).build();
    }
}
