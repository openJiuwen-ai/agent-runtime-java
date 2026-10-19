/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;

import org.a2aproject.sdk.spec.Task;

import java.util.Objects;

/** Shared default and custom result selection for the default caller and callback path. */
public final class RemoteTaskResultSupport {
    private RemoteTaskResultSupport() {
    }

    /**
     * Applies a configured result hook, or the existing Task/Status fallback when absent.
     *
     * @param task original aggregated Task
     * @param statusText current Status text from the existing outcome path
     * @param hook optional custom result hook
     * @return selected result
     */
    public static String extract(Task task, String statusText, RemoteTaskResultHook hook) {
        if (hook == null) {
            String taskText = A2aPartContent.extractTaskResult(task);
            return taskText.isBlank() ? Objects.requireNonNullElse(statusText, "") : taskText;
        }
        try {
            return Objects.requireNonNull(hook.extract(task), "RemoteTaskResultHook must not return null");
        } catch (RuntimeException cause) {
            if (cause instanceof ResultHookException exception) {
                throw exception;
            }
            throw new ResultHookException("Remote task result extraction failed", cause);
        }
    }

    /** Internal marker allowing the batch mapper to classify hook failures before remote errors. */
    public static final class ResultHookException extends RuntimeException {
        public ResultHookException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
