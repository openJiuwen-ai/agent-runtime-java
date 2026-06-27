/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import lombok.Data;

/**
 * Single SSE / stream chunk envelope.
 */
@Data
public class QueryChunk {

    /** Standard chunk types */
    public static final String TYPE_INTERRUPT = "interrupt";
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_CHUNK = "chunk";
    public static final String TYPE_ERROR = "error";

    private String type = TYPE_CHUNK;
    private Object data;

    public QueryChunk() {
    }

    public QueryChunk(String type, Object data) {
        this.type = type;
        this.data = data;
    }
}
