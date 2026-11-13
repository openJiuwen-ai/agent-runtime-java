/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

/**
 * Result of one session management orchestration (pause / resume / terminate).
 *
 * @param isAccepted whether the request was accepted for asynchronous processing
 * @param state session state after the orchestration decision
 * @param isAlreadyInState whether the session was already in the requested
 *                       state, making this call an idempotent no-op
 * @param detail human-readable orchestration detail for audit
 * @since 0.1.5
 */
public record SessionOperationResult(boolean isAccepted, SessionState state, boolean isAlreadyInState,
        String detail) {

    /**
     * Build an accepted result for a state transition now in progress.
     *
     * @param state target state of the transition
     * @param detail orchestration detail for audit
     * @return accepted result
     */
    public static SessionOperationResult accepted(SessionState state, String detail) {
        return new SessionOperationResult(true, state, false, detail);
    }

    /**
     * Build an idempotent result for a session already in the requested state.
     *
     * @param state current state of the session
     * @param detail orchestration detail for audit
     * @return idempotent result
     */
    public static SessionOperationResult alreadyInState(SessionState state, String detail) {
        return new SessionOperationResult(true, state, true, detail);
    }

    /**
     * Build a rejection result for a state-conflicted request.
     *
     * @param state current state of the session
     * @param detail conflict detail for the error body
     * @return rejected result
     */
    public static SessionOperationResult rejected(SessionState state, String detail) {
        return new SessionOperationResult(false, state, false, detail);
    }
}
