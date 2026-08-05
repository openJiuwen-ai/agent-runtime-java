/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class RemoteCallEventConsumerTest {
    @Test
    void forwardsArtifactsAlreadyPresentInWorkingTaskSnapshot() {
        Artifact artifact = Artifact.builder().artifactId("artifact-1")
                .parts(new TextPart("first chunk")).build();
        Task task = Task.builder().id("task-1").contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .artifacts(List.of(artifact)).build();
        List<QueryChunk> chunks = new ArrayList<>();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        new RemoteCallEventConsumer().accept(new TaskEvent(task), result, observer(chunks), null, false);

        assertThat(chunks).singleElement().extracting(QueryChunk::getData).isEqualTo("first chunk");
        assertThat(result).isNotDone();
    }

    @Test
    void doesNotReplayAllArtifactsFromTerminalTaskSnapshot() {
        Artifact artifact = Artifact.builder().artifactId("artifact-1")
                .parts(new TextPart("final result")).build();
        Task task = Task.builder().id("task-1").contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(artifact)).build();
        List<QueryChunk> chunks = new ArrayList<>();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();

        new RemoteCallEventConsumer().accept(new TaskEvent(task), result, observer(chunks), null, false);

        assertThat(chunks).isEmpty();
        assertThat(result.join().result()).isEqualTo("final result");
    }

    private static QueryStreamObserver observer(List<QueryChunk> chunks) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
