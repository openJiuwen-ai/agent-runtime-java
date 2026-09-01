/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.exception;

import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import java.util.Objects;

/**
 * Agent execution failure carrying a stable, framework-neutral descriptor.
 *
 * <p>Agent framework adapters may use this exception when callers need to
 * preserve a programmatic error code across the service boundary. Ordinary
 * runtime exceptions remain valid and are handled as generic execution failures.
 *
 * @since 0.1.0
 */
public class AgentExecutionException extends RuntimeException {
    private final AgentFailureDescriptor descriptor;

    /**
     * Creates a structured agent execution failure.
     *
     * @param message public failure message
     * @param descriptor stable failure descriptor
     * @param cause originating framework error
     */
    public AgentExecutionException(String message, AgentFailureDescriptor descriptor, Throwable cause) {
        super(message, cause);
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
    }

    /**
     * Returns the stable failure descriptor.
     *
     * @return failure descriptor
     */
    public AgentFailureDescriptor getDescriptor() {
        return descriptor;
    }
}
