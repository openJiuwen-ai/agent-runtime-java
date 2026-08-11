/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;
import java.util.Optional;

/**
 * Maps transport-neutral A2A responses to the outcome consumed by Runtime orchestration.
 *
 * <p>Transport-specific callers should decode their wire payloads into standard A2A
 * objects and delegate the business-state mapping to this class.
 *
 * @since 0.1.1
 */
public final class RemoteCallOutcomeMapper {
    /**
     * Maps a Task state observation when it represents a Runtime-visible outcome.
     *
     * @param taskId remote task identifier
     * @param state observed A2A task state
     * @param statusText text carried by the task status message
     * @param task optional complete task snapshot
     * @param isCallbackMode whether a non-final task means that callback completion is pending
     * @return an outcome for final, interrupted, or callback-pending state; otherwise empty
     */
    public Optional<RemoteCallOutcome> mapTask(String taskId, TaskState state, String statusText, Task task,
            boolean isCallbackMode) {
        String normalizedStatusText = statusText == null ? "" : statusText;
        if (isCallbackMode && state != null && !state.isFinal()) {
            return Optional.of(new RemoteCallOutcome(taskId, TaskState.TASK_STATE_INPUT_REQUIRED,
                    "INPUT_REQUIRED", null, "Remote callback pending"));
        }
        if (state != null && state.isInterrupted()) {
            String inputPrompt = normalizedStatusText.isBlank()
                    ? "Remote agent requires input"
                    : normalizedStatusText;
            return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), null, inputPrompt));
        }
        if (state == null || !state.isFinal()) {
            return Optional.empty();
        }
        String taskText = task == null ? "" : A2aPartContent.extractTaskResult(task);
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? normalizedStatusText : taskText)
                : (normalizedStatusText.isBlank() ? taskText : normalizedStatusText);
        return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), resultText, null));
    }

    /**
     * Maps a complete A2A Task snapshot.
     *
     * @param task task snapshot
     * @param isCallbackMode whether a non-final task means that callback completion is pending
     * @return mapped outcome when the snapshot represents a Runtime-visible outcome
     */
    public Optional<RemoteCallOutcome> mapTask(Task task, boolean isCallbackMode) {
        if (task == null || task.status() == null) {
            return Optional.empty();
        }
        String statusText = task.status().message() == null ? "" : extractText(task.status().message().parts());
        return mapTask(task.id(), task.status().state(), statusText, task, isCallbackMode);
    }

    /**
     * Maps a standalone A2A Message response.
     *
     * @param message response message
     * @return completed outcome, or empty for a missing message
     */
    public Optional<RemoteCallOutcome> mapMessage(Message message) {
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
                A2aPartContent.extract(message.parts()), null));
    }

    /**
     * Returns the stable Runtime result category for an A2A task state.
     *
     * @param state A2A task state
     * @return Runtime result category
     */
    public static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_" + state.name().replaceFirst("^TASK_STATE_", "");
        };
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
}
