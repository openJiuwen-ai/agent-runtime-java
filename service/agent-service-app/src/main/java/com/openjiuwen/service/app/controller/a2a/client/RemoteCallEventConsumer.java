/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Applies decoded A2A client events to a Runtime remote-call future and stream observer.
 *
 * @since 0.1.1
 */
public final class RemoteCallEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteCallEventConsumer.class);

    private final RemoteCallOutcomeMapper outcomeMapper;

    /**
     * Creates the event consumer with the standard Runtime outcome mapper.
     */
    public RemoteCallEventConsumer() {
        this.outcomeMapper = new RemoteCallOutcomeMapper();
    }

    /**
     * Applies one A2A event.
     *
     * @param event decoded SDK client event
     * @param result owning remote-call future
     * @param streamObserver optional business chunk observer
     * @param remoteTaskIdObserver optional task-id observer
     * @param isCallbackMode whether terminal completion is delivered by callback
     */
    public void accept(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver, boolean isCallbackMode) {
        if (event instanceof TaskUpdateEvent update) {
            handleUpdate(update, result, streamObserver, remoteTaskIdObserver, isCallbackMode);
        } else if (event instanceof TaskEvent taskEvent) {
            acceptTask(taskEvent.getTask(), result, streamObserver, remoteTaskIdObserver, isCallbackMode);
        } else if (event instanceof MessageEvent messageEvent) {
            completeMessage(messageEvent.getMessage(), result, remoteTaskIdObserver);
        } else {
            LOG.debug("Unknown A2A client event type: {}", event.getClass().getSimpleName());
        }
    }

    private void acceptTask(Task task, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver, Consumer<String> taskIdObserver, boolean isCallbackMode) {
        if (task != null && task.status() != null && task.status().state() != null
                && !task.status().state().isFinal() && task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                emitArtifact(artifact, result, streamObserver);
            }
        }
        completeTask(task, result, taskIdObserver, isCallbackMode);
    }

    private void handleUpdate(TaskUpdateEvent update, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver, Consumer<String> taskIdObserver, boolean isCallbackMode) {
        Object updateEvent = update.getUpdateEvent();
        if (updateEvent instanceof TaskArtifactUpdateEvent artifactUpdate) {
            notifyTaskId(taskIdObserver, artifactUpdate.taskId(), update.getTask().status().state());
            acceptArtifact(artifactUpdate, result, streamObserver);
        } else if (updateEvent instanceof TaskStatusUpdateEvent statusUpdate) {
            acceptStatus(statusUpdate, update.getTask(), result, taskIdObserver, isCallbackMode);
        } else {
            LOG.debug("Unknown A2A task update event type: {}", typeName(updateEvent));
        }
    }

    void acceptArtifact(TaskArtifactUpdateEvent artifactUpdate, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver) {
        emitArtifact(artifactUpdate.artifact(), result, streamObserver);
    }

    void acceptStatus(TaskStatusUpdateEvent statusUpdate, Task task, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> taskIdObserver, boolean isCallbackMode) {
        String statusText = statusUpdate.status().message() == null
                ? "" : extractText(statusUpdate.status().message().parts());
        TaskObservation observation = new TaskObservation(statusUpdate.taskId(), statusUpdate.status().state(),
                statusText, task);
        completeTask(observation, result, taskIdObserver, isCallbackMode);
    }

    private void completeTask(Task task, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> taskIdObserver, boolean isCallbackMode) {
        TaskState state = task.status().state();
        String statusText = task.status().message() == null ? "" : extractText(task.status().message().parts());
        completeTask(new TaskObservation(task.id(), state, statusText, task), result, taskIdObserver, isCallbackMode);
    }

    private void completeTask(TaskObservation observation, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> taskIdObserver, boolean isCallbackMode) {
        notifyTaskId(taskIdObserver, observation.taskId(), observation.state());
        if (!result.isDone()) {
            outcomeMapper.mapTask(observation.taskId(), observation.state(), observation.statusText(),
                    observation.task(), isCallbackMode).ifPresent(result::complete);
        }
    }

    private void completeMessage(Message message, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> taskIdObserver) {
        if (message == null || result.isDone()) {
            return;
        }
        notifyTaskId(taskIdObserver, message.taskId(), TaskState.TASK_STATE_COMPLETED);
        outcomeMapper.mapMessage(message).ifPresent(result::complete);
    }

    private static void emitArtifact(Artifact artifact, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver) {
        if (result.isDone() || streamObserver == null || artifact == null || artifact.parts() == null) {
            return;
        }
        for (Part<?> part : artifact.parts()) {
            if (part instanceof TextPart textPart && !textPart.text().isEmpty()) {
                streamObserver.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, textPart.text()));
            } else if (part instanceof DataPart dataPart) {
                streamObserver.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, dataPart.data()));
            } else {
                LOG.debug("Unsupported A2A artifact part type: {}", typeName(part));
            }
        }
    }

    private static void notifyTaskId(Consumer<String> observer, String taskId, TaskState state) {
        if (observer == null || taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            observer.accept(taskId);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Remote task ID observer rejected update taskId={} state={}", taskId, state, failure);
        }
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart value) {
                text.append(value.text());
            }
        }
        return text.toString();
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private record TaskObservation(String taskId, TaskState state, String statusText, Task task) {
    }
}
