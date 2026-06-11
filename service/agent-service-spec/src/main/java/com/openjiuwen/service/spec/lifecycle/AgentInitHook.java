package com.openjiuwen.service.spec.lifecycle;

/**
 * Hook invoked during the AgentApp init phase (after Spring context is ready).
 */
public interface AgentInitHook {

    void onInit(AgentLifecycleContext context) throws Exception;
}
