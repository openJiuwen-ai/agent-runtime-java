/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.TaskState;

/**
 * Structured terminal or input-required result for a coordinator-owned
 * {@link RemoteAgentCaller#callOutcome} invocation.
 *
 * @param remoteTaskId   the remote task id (may be {@code null} if never reported)
 * @param remoteState    terminal {@link TaskState} reported by the remote
 * @param resultCategory coarse category derived from {@code remoteState}
 *                       (e.g. {@code COMPLETED}, {@code INPUT_REQUIRED},
 *                       {@code REMOTE_BUSINESS_FAILURE})
 * @param result         the resolved business text; {@code null} when the
 *                       remote is in a non-final or input-required state
 * @param inputPrompt    the input-required prompt; non-{@code null} only when
 *                       {@code remoteState} is interrupted
 */
public record RemoteCallOutcome(String remoteTaskId, TaskState remoteState, String resultCategory, String result,
        String inputPrompt) {
}
