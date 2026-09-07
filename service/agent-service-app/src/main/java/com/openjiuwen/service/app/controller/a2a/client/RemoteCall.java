/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * @param protocolTenant A2A protocol tenant, not an authenticated tenant identity
 * @param isCallerStreaming whether the current inbound request is streaming;
 *                          gates remote A2A streaming so a non-streaming caller
 *                          never receives streamed artifacts
 * @param parts           normalized Part list mapped back to
 *                        outbound SDK parts after the leading text part, or {@code null}
 *                        when the call carries text only
 */
public record RemoteCall(String agentName, String message, String contextId, String taskId,
        Map<String, Object> metadata, Map<String, Object> messageMetadata, String protocolTenant,
        boolean isCallerStreaming, List<Map<String, Object>> parts) {
    public RemoteCall {
        metadata = immutableMetadata(metadata);
        messageMetadata = immutableMetadata(messageMetadata);
    }

    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata, Map<String, Object> messageMetadata) {
        this(agentName, message, contextId, taskId, metadata, messageMetadata, null, false, null);
    }

    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata) {
        this(agentName, message, contextId, taskId, metadata, null, null, false, null);
    }

    /**
     * Compatibility constructor preserving existing call sites.
     *
     * @param agentName remote agent name
     * @param message message text
     * @param contextId conversation context identifier
     * @param taskId remote task identifier
     * @param metadata params-level metadata
     * @param messageMetadata message-level metadata
     * @param isCallerStreaming whether the caller accepts streaming
     */
    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata, Map<String, Object> messageMetadata, boolean isCallerStreaming) {
        this(agentName, message, contextId, taskId, metadata, messageMetadata, null, isCallerStreaming, null);
    }

    /**
     * Creates a call with normalized parts and no protocol tenant.
     *
     * @param agentName remote agent name
     * @param message message text
     * @param contextId conversation context identifier
     * @param taskId remote task identifier
     * @param metadata params-level metadata
     * @param messageMetadata message-level metadata
     * @param isCallerStreaming whether the caller accepts streaming
     * @param parts normalized outbound parts
     */
    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata, Map<String, Object> messageMetadata, boolean isCallerStreaming,
            List<Map<String, Object>> parts) {
        this(agentName, message, contextId, taskId, metadata, messageMetadata, null, isCallerStreaming, parts);
    }

    /**
     * Creates a tenant-aware call without normalized parts.
     *
     * @param agentName remote agent name
     * @param message message text
     * @param contextId conversation context identifier
     * @param taskId remote task identifier
     * @param metadata params-level metadata
     * @param messageMetadata message-level metadata
     * @param protocolTenant A2A protocol tenant, not an authenticated identity
     * @param isCallerStreaming whether the caller accepts streaming
     */
    public RemoteCall(String agentName, String message, String contextId, String taskId,
            Map<String, Object> metadata, Map<String, Object> messageMetadata, String protocolTenant,
            boolean isCallerStreaming) {
        this(agentName, message, contextId, taskId, metadata, messageMetadata, protocolTenant, isCallerStreaming, null);
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
