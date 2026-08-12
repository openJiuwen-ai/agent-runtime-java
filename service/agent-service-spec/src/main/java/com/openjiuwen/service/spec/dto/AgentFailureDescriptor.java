/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

/**
 * Framework-neutral description of an agent execution failure.
 *
 * @param code stable symbolic error code
 * @param numericCode optional framework-specific numeric code
 * @param retryable whether the originating framework marks the failure retryable
 * @since 0.1.0
 */
public record AgentFailureDescriptor(String code, Integer numericCode, boolean retryable) {
    /**
     * Creates a failure descriptor.
     *
     * @throws IllegalArgumentException when {@code code} is blank
     */
    public AgentFailureDescriptor {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
