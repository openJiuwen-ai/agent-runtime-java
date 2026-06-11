package com.openjiuwen.service.spec.lifecycle;

/**
 * Optional hook during the AgentApp init phase (after Spring context is ready).
 * <p>Agent loading is performed by Agent Service; use this for warmup or auxiliary setup only.
 */
public interface AgentInitHook {

    void onInit(AgentLifecycleContext context) throws Exception;
}
