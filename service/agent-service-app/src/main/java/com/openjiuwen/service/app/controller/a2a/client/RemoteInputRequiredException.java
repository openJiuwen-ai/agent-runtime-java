/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Signals that a remote agent returned {@code INPUT_REQUIRED} and is waiting for
 * additional input before it can complete.
 *
 * <p>This is a SPI-level type: implementations of {@link RemoteAgentCaller} throw
 * (or complete exceptionally with) this exception to tell the orchestrator that
 * the remote task is suspended. The {@code remoteTaskId} carries the upstream
 * task id needed to resume the conversation later.
 *
 * @since 0.1.0
 */
public class RemoteInputRequiredException extends RuntimeException {
    private final String remoteTaskId;

    /**
     * Constructs a new remote input-required signal.
     *
     * @param message       human-readable description of the input needed
     * @param remoteTaskId  the remote task id to resume later; may be empty when
     *                      the upstream did not provide one
     */
    public RemoteInputRequiredException(String message, String remoteTaskId) {
        super(message);
        this.remoteTaskId = remoteTaskId;
    }

    /**
     * Returns the remote task id needed to resume the suspended conversation.
     *
     * @return the remote task id, or empty string if the upstream did not provide one
     */
    public String getRemoteTaskId() {
        return remoteTaskId;
    }
}
