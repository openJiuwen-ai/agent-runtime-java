/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import lombok.Data;

/**
 * Single SSE / stream chunk envelope.
 *
 * @since 0.1.0
 */
@Data
public class QueryChunk {
    /** Interrupt chunk type, used for input-required signals. */
    public static final String TYPE_INTERRUPT = "interrupt";

    /** Streaming intermediate chunk type. */
    public static final String TYPE_CHUNK = "chunk";

    /** Remote agent business output carrying source provenance. */
    public static final String TYPE_REMOTE_AGENT_OUTPUT = "remote_agent_output";

    /**
     * Cancel chunk type: the execution side asks for this task to end in the canceled state.
     *
     * <p>Symmetric to {@link #TYPE_INTERRUPT}: the sender declares an intent and the runtime
     * performs the state transition, so there is still exactly one lifecycle writer. It does not
     * mean "abandon this piece of work" in a business sense — a framework adapter with such a
     * notion should express it through its own mechanism. The payload may carry an optional reason
     * for logs and audit; the runtime does not parse its structure.</p>
     */
    public static final String TYPE_CANCEL = "cancel";

    /** Error chunk type. */
    public static final String TYPE_ERROR = "error";

    private String type = TYPE_CHUNK;

    private Object data;

    /**
     * Default constructor for JSON deserialization.
     */
    public QueryChunk() {
    }

    /**
     * Creates a stream chunk with explicit type and payload.
     *
     * @param type the chunk type
     * @param data the chunk payload
     */
    public QueryChunk(String type, Object data) {
        this.type = type;
        this.data = data;
    }
}
