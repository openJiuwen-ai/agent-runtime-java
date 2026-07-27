/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameter object for a remote agent call: the addressing and payload
 * coordinates shared by {@link RemoteAgentCaller#callOutcome} callers.
 *
 * @param agentName       registered remote agent name
 * @param message         text payload to send
 * @param contextId       conversation context ID (shared across calls to the same remote)
 * @param taskId          remote task ID to resume, or {@code null} for a new task
 * @param metadata        params-level metadata
 * @param messageMetadata message-level metadata
 * @param isCallerStreaming whether the current inbound request is streaming;
 *                          gates remote A2A streaming so a non-streaming caller
 *                          never receives streamed artifacts
 */
public record RemoteCall(String agentName, String message, String contextId, String taskId,
        Map<String, Object> metadata, Map<String, Object> messageMetadata, boolean isCallerStreaming) {
    public RemoteCall {
        metadata = immutableMetadata(metadata);
        messageMetadata = immutableMetadata(messageMetadata);
    }

    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata, Map<String, Object> messageMetadata) {
        this(agentName, message, contextId, taskId, metadata, messageMetadata, false);
    }

    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata) {
        this(agentName, message, contextId, taskId, metadata, null, false);
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
