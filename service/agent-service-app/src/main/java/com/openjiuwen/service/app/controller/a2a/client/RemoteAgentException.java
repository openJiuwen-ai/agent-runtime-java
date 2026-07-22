/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Wraps failures observed while invoking a remote agent through the
 * {@link RemoteAgentCaller} SPI (transport errors, timeouts, execution failures).
 *
 * <p>Implementations of {@link RemoteAgentCaller} report such failures via
 * {@code observer.onError(...)} using this exception type so the orchestrator
 * can distinguish remote-call failures from other runtime errors.
 *
 * @since 0.1.0
 */
public class RemoteAgentException extends RuntimeException {
    /**
     * Constructs a new remote agent exception.
     *
     * @param message human-readable description of the failure
     * @param cause   the underlying cause, or {@code null} if none
     */
    public RemoteAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
