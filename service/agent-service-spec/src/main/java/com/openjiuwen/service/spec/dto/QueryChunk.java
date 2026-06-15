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

    private String type = "chunk";
    private Object data;

    public QueryChunk() {
    }

    public QueryChunk(String type, Object data) {
        this.type = type;
        this.data = data;
    }
}
