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

    /** Error chunk type. */
    public static final String TYPE_ERROR = "error";

    private String type = TYPE_CHUNK;
    private Object data;

    /** Default constructor for JSON deserialization. */
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
