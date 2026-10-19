/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.Task;

/**
 * Extracts the business result from an SDK-aggregated remote Task.
 * Implementations must treat the Task and its nested data as read-only.
 */
@FunctionalInterface
public interface RemoteTaskResultHook {
    /**
     * @param task original SDK-aggregated Task, including its current Status and history
     * @return result string; empty is valid, {@code null} is an implementation error
     */
    String extract(Task task);
}
