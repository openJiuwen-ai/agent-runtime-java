package com.openjiuwen.service.app.lifecycle;

/**
 * AgentApp init / shutdown / interrupt lifecycle coordinator (Issue #5).
 */
public interface AgentLifecycleManager {

    void runInitPhase();

    void runShutdownPhase();

    void interrupt(String conversationId);
}
