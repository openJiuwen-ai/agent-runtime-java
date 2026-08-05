/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests the A2A-to-Runtime outcome contract shared by HTTP and Bus callers.
 *
 * @since 0.1.1
 */
class RemoteCallOutcomeMapperTest {
    private final RemoteCallOutcomeMapper mapper = new RemoteCallOutcomeMapper();

    @Test
    void ignoresOrdinaryNonFinalState() {
        assertThat(mapper.mapTask("task-1", TaskState.TASK_STATE_WORKING, "working", null, false)).isEmpty();
    }

    @Test
    void mapsInputRequiredPrompt() {
        RemoteCallOutcome outcome = mapper
                .mapTask("task-1", TaskState.TASK_STATE_INPUT_REQUIRED, "Provide an account", null, false)
                .orElseThrow();

        assertThat(outcome.remoteTaskId()).isEqualTo("task-1");
        assertThat(outcome.resultCategory()).isEqualTo("INPUT_REQUIRED");
        assertThat(outcome.inputPrompt()).isEqualTo("Provide an account");
    }

    @Test
    void mapsCompletedTaskArtifact() {
        Artifact artifact = new Artifact("artifact-1", null, null,
                List.<Part<?>>of(new TextPart("business result")), Map.of(), List.of());
        Task task = Task.builder().id("task-1").contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).artifacts(List.of(artifact)).build();

        RemoteCallOutcome outcome = mapper.mapTask(task, false).orElseThrow();

        assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(outcome.result()).isEqualTo("business result");
    }

    @Test
    void mapsStandaloneMessageAsCompleted() {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).taskId("task-1").contextId("context-1")
                .parts(List.<Part<?>>of(new TextPart("message result"))).build();

        RemoteCallOutcome outcome = mapper.mapMessage(message).orElseThrow();

        assertThat(outcome.remoteTaskId()).isEqualTo("task-1");
        assertThat(outcome.resultCategory()).isEqualTo("COMPLETED");
        assertThat(outcome.result()).isEqualTo("message result");
    }
}
