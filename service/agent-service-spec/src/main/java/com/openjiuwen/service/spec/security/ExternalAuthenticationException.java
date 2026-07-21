/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

/**
 * Raised when outbound authentication fails and must not be treated as {@link AuthMaterial#none()}.
 *
 * @since 0.1.0
 */
public class ExternalAuthenticationException extends RuntimeException {
    /**
     * Creates an outbound authentication failure.
     *
     * @param message failure description
     */
    public ExternalAuthenticationException(String message) {
        super(message);
    }

    /**
     * Creates an outbound authentication failure with a cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public ExternalAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
