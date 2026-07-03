/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Optional hook notified when a conversation execution is interrupted.
 *
 * @since 0.1.0
 */
public interface AgentInterruptHandler {

    /**
     * Handles an execution interrupt for a conversation.
     *
     * @param conversationId the conversation identifier
     * @param reason the interrupt reason
     */
    void interrupt(String conversationId, InterruptReason reason);
}
