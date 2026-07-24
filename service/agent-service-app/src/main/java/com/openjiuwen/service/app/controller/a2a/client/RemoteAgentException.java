/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Wraps failures observed while invoking a remote agent through the
 * {@link RemoteAgentCaller} SPI (transport errors, timeouts, execution failures,
 * premature stream closure).
 *
 * <p>Implementations of {@link RemoteAgentCaller} report such failures via
 * {@code observer.onError(...)} using this exception type so the orchestrator
 * can distinguish remote-call failures from other runtime errors. The
 * {@link #getCode() code} field carries a structured failure category that lets
 * upstream layers decide whether the failure is recoverable (e.g. resume the
 * parent agent with an error tool result) or terminal.
 *
 * @since 0.1.0
 */
public class RemoteAgentException extends RuntimeException {
    /** Structured code for a remote call timeout. */
    public static final String CODE_REMOTE_TIMEOUT = "REMOTE_TIMEOUT";

    /** Structured code for a stream that ended before a terminal event. */
    public static final String CODE_REMOTE_STREAM_CLOSED = "REMOTE_STREAM_CLOSED";

    /** Structured code for other remote call failures. */
    public static final String CODE_REMOTE_ERROR = "REMOTE_ERROR";

    private final String code;

    /**
     * Constructs a generic remote failure for compatibility with existing
     * callers.
     *
     * @param message human-readable description of the failure
     * @param cause   the underlying cause, or {@code null} if none
     */
    public RemoteAgentException(String message, Throwable cause) {
        this(CODE_REMOTE_ERROR, message, cause);
    }

    /**
     * Constructs a remote failure with a structured code.
     *
     * @param code    structured failure category (see {@link #CODE_REMOTE_TIMEOUT}
     *                / {@link #CODE_REMOTE_STREAM_CLOSED} / {@link #CODE_REMOTE_ERROR});
     *                defaults to {@link #CODE_REMOTE_ERROR} when blank
     * @param message human-readable description of the failure
     * @param cause   the underlying cause, or {@code null} if none
     */
    public RemoteAgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.isBlank() ? CODE_REMOTE_ERROR : code;
    }

    /**
     * Returns the structured code categorising this remote call failure.
     *
     * @return the structured remote failure code
     */
    public String getCode() {
        return code;
    }
}
