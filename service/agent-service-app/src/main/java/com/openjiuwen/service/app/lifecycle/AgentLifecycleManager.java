/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

/**
 * AgentApp init / shutdown / interrupt lifecycle coordinator (Issue #5).
 *
 * @since 0.1.0
 */
public interface AgentLifecycleManager {
    /**
     * Runs the agent init phase.
     */
    void runInitPhase();

    /**
     * Runs the agent shutdown phase.
     */
    void runShutdownPhase();

    /**
     * Interrupts active execution for a conversation.
     *
     * @param conversationId the conversation identifier
     */
    void interrupt(String conversationId);
}
