/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.AgentError;

/**
 * Adapter-neutral execution failure with a stable error descriptor.
 *
 * @since 0.1.0
 */
public class AgentExecutionException extends RuntimeException {
    private final AgentError error;

    /**
     * Creates a structured agent execution failure.
     *
     * @param message public failure message
     * @param error stable error descriptor
     * @param cause originating framework error
     */
    public AgentExecutionException(String message, AgentError error, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    /**
     * Returns the stable error descriptor.
     *
     * @return error descriptor
     */
    public AgentError getError() {
        return error;
    }
}
