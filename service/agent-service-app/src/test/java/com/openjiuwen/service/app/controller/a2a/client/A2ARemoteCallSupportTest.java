/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class A2ARemoteCallSupportTest {
    private final A2ARemoteCallSupport support = new A2ARemoteCallSupport();

    @Test
    void mapsInterruptedAndCompletedResultsConsistently() {
        RemoteCallOutcome interrupted = support.mapTask("task-1", TaskState.TASK_STATE_INPUT_REQUIRED,
                "Provide an account", null, null, false).orElseThrow();
        Task completedTask = task(TaskState.TASK_STATE_COMPLETED, "business result");
        RemoteCallOutcome completed = support.mapTask(completedTask, false).orElseThrow();

        assertThat(interrupted.resultCategory()).isEqualTo("INPUT_REQUIRED");
        assertThat(interrupted.inputPrompt()).isEqualTo("Provide an account");
        assertThat(completed.resultCategory()).isEqualTo("COMPLETED");
        assertThat(completed.result()).isEqualTo("business result");
    }

    @Test
    void mapsStandaloneMessageAsCompleted() {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).taskId("task-1").contextId("context-1")
                .parts(List.<Part<?>>of(new TextPart("message result"))).build();

        RemoteCallOutcome outcome = support.mapMessage(message).orElseThrow();

        assertThat(outcome.remoteTaskId()).isEqualTo("task-1");
        assertThat(outcome.result()).isEqualTo("message result");
    }

    @Test
    void reportsTaskStatusWithoutReplayingSnapshotArtifacts() {
        RecordingObserver observer = new RecordingObserver();
        CompletableFuture<RemoteCallOutcome> working = new CompletableFuture<>();
        CompletableFuture<RemoteCallOutcome> completed = new CompletableFuture<>();

        support.accept(new TaskEvent(task(TaskState.TASK_STATE_WORKING, "first chunk")), working, observer,
                false, true);
        support.accept(new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, "final result")), completed, observer,
                false, true);

        assertThat(observer.artifacts).isEmpty();
        assertThat(observer.statuses).hasSize(2);
        assertThat(working).isNotDone();
        assertThat(completed.join().result()).isEqualTo("final result");
    }

    @Test
    void observerFailureCompletesCallExceptionally() {
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        RemoteAgentCaller.EventObserver observer = new RemoteAgentCaller.EventObserver() {
            @Override
            public void onStatus(TaskStatusUpdateEvent event) {
                throw new IllegalStateException("observer rejected status");
            }

            @Override
            public void onArtifact(TaskArtifactUpdateEvent event) {
            }
        };

        support.accept(new TaskEvent(task(TaskState.TASK_STATE_WORKING, "first chunk")), result, observer,
                false, true);

        assertThatThrownBy(result::join).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static Task task(TaskState state, String text) {
        Artifact artifact = new Artifact("artifact-1", null, null,
                List.<Part<?>>of(new TextPart(text)), Map.of(), List.of());
        return Task.builder().id("task-1").contextId("context-1")
                .status(new TaskStatus(state)).artifacts(List.of(artifact)).build();
    }

    private static final class RecordingObserver implements RemoteAgentCaller.EventObserver {
        private final List<TaskStatusUpdateEvent> statuses = new ArrayList<>();
        private final List<TaskArtifactUpdateEvent> artifacts = new ArrayList<>();

        @Override
        public void onStatus(TaskStatusUpdateEvent event) {
            statuses.add(event);
        }

        @Override
        public void onArtifact(TaskArtifactUpdateEvent event) {
            artifacts.add(event);
        }
    }
}
