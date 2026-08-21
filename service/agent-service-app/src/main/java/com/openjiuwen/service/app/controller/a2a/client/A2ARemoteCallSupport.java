/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.controller.a2a.A2aErrorMetadata;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Shared, transport-independent preparation and outcome semantics for remote A2A calls.
 *
 * @since 0.1.0
 */
public final class A2ARemoteCallSupport {
    /** Callback URL metadata key supported by the HTTP caller. */
    public static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";
    /** Callback token metadata key supported by the HTTP caller. */
    public static final String CALLBACK_TOKEN_METADATA = "runtime.a2a.callbackToken";
    /** Callback id metadata key supported by the HTTP caller. */
    public static final String CALLBACK_ID_METADATA = "runtime.a2a.callbackId";

    private static final Logger log = LoggerFactory.getLogger(A2ARemoteCallSupport.class);

    /** Builds the standard SDK request parameters without owning transport state. */
    public MessageSendParams buildSendParams(RemoteCall call, String contextId) {
        var messageBuilder = Message.builder().role(Message.Role.ROLE_USER).contextId(contextId)
                .parts(List.<Part<?>>of(new TextPart(call.message()))).metadata(call.messageMetadata());
        if (call.taskId() != null && !call.taskId().isBlank()) {
            messageBuilder.taskId(call.taskId());
        }
        var configurationBuilder = MessageSendConfiguration.builder().returnImmediately(false);
        callbackConfig(call, contextId)
                .ifPresent(config -> configurationBuilder.returnImmediately(true).taskPushNotificationConfig(config));
        return MessageSendParams.builder().message(messageBuilder.build()).configuration(configurationBuilder.build())
                .metadata(paramsMetadata(call.metadata())).build();
    }

    /** Returns whether the call asks for HTTP push callback mode. */
    public boolean isCallbackRequested(RemoteCall call) {
        Object rawUrl = call.metadata().get(CALLBACK_URL_METADATA);
        return rawUrl instanceof String url && !url.isBlank();
    }

    /** Returns whether already prepared parameters use callback mode. */
    public boolean isCallbackMode(MessageSendParams params) {
        return params != null && params.configuration() != null
                && params.configuration().taskPushNotificationConfig() != null;
    }

    /** Applies one standard SDK client event to the shared Runtime observer and outcome. */
    public void accept(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean callbackMode, boolean streaming) {
        try {
            if (event instanceof TaskUpdateEvent update) {
                Object updateEvent = update.getUpdateEvent();
                if (updateEvent instanceof TaskArtifactUpdateEvent artifact) {
                    if (!result.isDone()) {
                        eventObserver.onArtifact(artifact);
                    }
                } else if (updateEvent instanceof TaskStatusUpdateEvent status) {
                    acceptStatus(status, update.getTask(), result, eventObserver, callbackMode);
                } else if (updateEvent != null) {
                    log.debug("Unknown update event type: {}", updateEvent.getClass().getSimpleName());
                }
            } else if (event instanceof TaskEvent task) {
                acceptTask(task, result, eventObserver, callbackMode, streaming);
            } else if (event instanceof MessageEvent message) {
                acceptMessage(message, result);
            } else if (event != null) {
                log.debug("Unknown event type: {}", event.getClass().getSimpleName());
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            result.completeExceptionally(failure);
        }
    }

    /** Completes an unfinished call exceptionally when its transport stream ends. */
    public boolean completeOnStreamEnd(String agentName, CompletableFuture<?> result, Throwable error) {
        if (result.isDone()) {
            return false;
        }
        Throwable failure = error == null
                ? new IllegalStateException(
                        "Remote agent '" + agentName + "' closed the stream before a terminal event")
                : error;
        return result.completeExceptionally(failure);
    }

    /** Maps a Task snapshot to an outcome when it is interrupted or terminal. */
    public Optional<RemoteCallOutcome> mapTask(Task task, boolean callbackMode) {
        if (task == null || task.status() == null) {
            return Optional.empty();
        }
        String statusText = task.status().message() == null ? "" : extractText(task.status().message().parts());
        return mapTask(task.id(), task.status().state(), statusText, task,
                remoteFailure(task.status().message()).orElse(null), callbackMode);
    }

    /** Maps explicit Task coordinates to a shared outcome. */
    public Optional<RemoteCallOutcome> mapTask(String taskId, TaskState state, String statusText, Task task,
            AgentFailureDescriptor remoteFailure, boolean callbackMode) {
        String normalizedStatusText = statusText == null ? "" : statusText;
        if (callbackMode && state != null && !state.isFinal()) {
            return Optional.of(new RemoteCallOutcome(taskId, TaskState.TASK_STATE_INPUT_REQUIRED,
                    "INPUT_REQUIRED", null, "Remote callback pending", remoteFailure));
        }
        if (state != null && state.isInterrupted()) {
            String prompt = normalizedStatusText.isBlank()
                    ? "Remote agent requires input" : normalizedStatusText;
            return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), null, prompt,
                    remoteFailure));
        }
        if (state == null || !state.isFinal()) {
            return Optional.empty();
        }
        String taskText = task == null ? "" : A2aPartContent.extractTaskResult(task);
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? normalizedStatusText : taskText)
                : (normalizedStatusText.isBlank() ? taskText : normalizedStatusText);
        return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), resultText, null,
                remoteFailure));
    }

    /** Maps a standalone A2A Message result to a completed outcome. */
    public Optional<RemoteCallOutcome> mapMessage(Message message) {
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
                A2aPartContent.extract(message.parts()), null));
    }

    /** Maps SDK Task states to stable Runtime result categories. */
    public static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        if (state == TaskState.TASK_STATE_COMPLETED) {
            return "COMPLETED";
        }
        if (state.isInterrupted()) {
            return "INPUT_REQUIRED";
        }
        if (state == TaskState.TASK_STATE_FAILED) {
            return "REMOTE_BUSINESS_FAILURE";
        }
        return "REMOTE_" + state.name().replaceFirst("^TASK_STATE_", "");
    }

    private void acceptStatus(TaskStatusUpdateEvent event, Task task,
            CompletableFuture<RemoteCallOutcome> result, RemoteAgentCaller.EventObserver eventObserver,
            boolean callbackMode) {
        if (result.isDone()) {
            return;
        }
        eventObserver.onStatus(event);
        String statusText = event.status().message() == null ? "" : extractText(event.status().message().parts());
        mapTask(event.taskId(), event.status().state(), statusText, task,
                remoteFailure(event.status().message()).orElse(null), callbackMode).ifPresent(result::complete);
    }

    private void acceptTask(TaskEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean callbackMode, boolean streaming) {
        if (result.isDone()) {
            return;
        }
        Task task = event.getTask();
        if (task == null || task.status() == null) {
            return;
        }
        if (streaming) {
            eventObserver.onStatus(new TaskStatusUpdateEvent(task.id(), task.status(), task.contextId(), Map.of()));
        }
        mapTask(task, callbackMode).ifPresent(result::complete);
    }

    private void acceptMessage(MessageEvent event, CompletableFuture<RemoteCallOutcome> result) {
        if (!result.isDone()) {
            mapMessage(event.getMessage()).ifPresent(result::complete);
        }
    }

    private static Map<String, Object> paramsMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove(CALLBACK_URL_METADATA);
        result.remove(CALLBACK_TOKEN_METADATA);
        result.remove(CALLBACK_ID_METADATA);
        return result;
    }

    private static Optional<TaskPushNotificationConfig> callbackConfig(RemoteCall call, String contextId) {
        Object rawUrl = call.metadata().get(CALLBACK_URL_METADATA);
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            return Optional.empty();
        }
        String id = Optional.ofNullable(call.metadata().get(CALLBACK_ID_METADATA)).map(String::valueOf)
                .filter(value -> !value.isBlank()).orElse("push-" + contextId);
        String token = Optional.ofNullable(call.metadata().get(CALLBACK_TOKEN_METADATA)).map(String::valueOf)
                .filter(value -> !value.isBlank()).orElse(null);
        return Optional.of(TaskPushNotificationConfig.builder().id(id).url(url).token(token).build());
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    private static Optional<AgentFailureDescriptor> remoteFailure(Message message) {
        return message == null ? Optional.empty() : A2aErrorMetadata.decode(message.metadata());
    }
}
