/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.Task;

/**
 * Extracts the business result from an SDK-aggregated remote Task.
 * Implementations must treat the Task and its nested data as read-only.
 *
 * @since 0.1.4
 */
@FunctionalInterface
public interface RemoteTaskResultHook {
    /**
     * Selects the tool result from the original aggregated Task.
     *
     * @param task original SDK-aggregated Task, including its current Status and history
     * @return result string; empty is valid, {@code null} is an implementation error
     */
    String extract(Task task);
}
