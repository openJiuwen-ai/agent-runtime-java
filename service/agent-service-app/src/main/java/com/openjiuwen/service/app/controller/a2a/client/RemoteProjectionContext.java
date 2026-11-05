/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Read-only correlation information for a remote event projection.
 *
 * @since 0.1.4
 */
public record RemoteProjectionContext(String remoteAgentName, String parentAgentId, String parentTaskId,
        String toolCallId) {
}
