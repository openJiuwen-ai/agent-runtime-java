/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Optional hook notified when a conversation execution is interrupted.
 */
public interface AgentInterruptHandler {

    void interrupt(String conversationId, InterruptReason reason);
}
