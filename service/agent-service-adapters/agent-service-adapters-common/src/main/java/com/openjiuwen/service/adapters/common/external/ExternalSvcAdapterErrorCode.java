/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * Error codes reported by external service adapters.
 *
 * @since 2026-06-24
 */
public enum ExternalSvcAdapterErrorCode {
    MCP_OUTBOUND_CALL_FAILED("EXT_MCP_001", "MCP outbound call failed"),
    MCP_CIRCUIT_OPEN("EXT_MCP_002", "MCP circuit breaker is open"),
    MCP_RETRY_INTERRUPTED("EXT_MCP_003", "MCP retry was interrupted"),
    MCP_TIMEOUT("EXT_MCP_004", "MCP outbound call timed out"),
    MCP_TLS_HANDSHAKE_FAILED("EXT_MCP_005", "MCP TLS handshake failed"),
    MCP_AUTH_FAILED("EXT_MCP_006", "MCP outbound authentication failed"),
    REMOTE_OUTBOUND_CALL_FAILED("EXT_REMOTE_001", "Remote outbound call failed"),
    REMOTE_CIRCUIT_OPEN("EXT_REMOTE_002", "Remote circuit breaker is open"),
    REMOTE_RETRY_INTERRUPTED("EXT_REMOTE_003", "Remote retry was interrupted"),
    REMOTE_STREAM_FAILED("EXT_REMOTE_004", "Remote stream call failed"),
    REMOTE_TIMEOUT("EXT_REMOTE_005", "Remote outbound call timed out"),
    REMOTE_TLS_HANDSHAKE_FAILED("EXT_REMOTE_006", "Remote TLS handshake failed"),
    REMOTE_AUTH_FAILED("EXT_REMOTE_007", "Remote outbound authentication failed"),
    SANDBOX_OUTBOUND_CALL_FAILED("EXT_SANDBOX_001", "Sandbox outbound call failed"),
    SANDBOX_CIRCUIT_OPEN("EXT_SANDBOX_002", "Sandbox circuit breaker is open"),
    SANDBOX_RETRY_INTERRUPTED("EXT_SANDBOX_003", "Sandbox retry was interrupted"),
    SANDBOX_TIMEOUT("EXT_SANDBOX_004", "Sandbox outbound call timed out"),
    SANDBOX_TLS_HANDSHAKE_FAILED("EXT_SANDBOX_005", "Sandbox TLS handshake failed"),
    SANDBOX_AUTH_FAILED("EXT_SANDBOX_006", "Sandbox outbound authentication failed"),
    MEMORY_OUTBOUND_CALL_FAILED("EXT_MEMORY_001", "Memory outbound call failed"),
    MEMORY_CIRCUIT_OPEN("EXT_MEMORY_002", "Memory circuit breaker is open"),
    MEMORY_RETRY_INTERRUPTED("EXT_MEMORY_003", "Memory retry was interrupted"),
    MEMORY_TIMEOUT("EXT_MEMORY_004", "Memory outbound call timed out");

    private final String code;

    private final String defaultMessage;

    ExternalSvcAdapterErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
