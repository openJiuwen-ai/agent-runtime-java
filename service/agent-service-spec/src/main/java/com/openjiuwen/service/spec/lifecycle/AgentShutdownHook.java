package com.openjiuwen.service.spec.lifecycle;

/**
 * Hook invoked during the AgentApp shutdown phase (before the context closes).
 */
public interface AgentShutdownHook {

    void onShutdown(AgentLifecycleContext context);
}
