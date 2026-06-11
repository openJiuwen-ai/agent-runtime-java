package com.openjiuwen.service.spec.lifecycle;

/**
 * Optional hook notified when a conversation execution is interrupted.
 */
public interface AgentInterruptHandler {

    void interrupt(String conversationId, InterruptReason reason);
}
