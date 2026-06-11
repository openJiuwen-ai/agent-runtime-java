package com.openjiuwen.service.spec.lifecycle;

/**
 * Hook invoked during the AgentApp init phase (after Spring context is ready).
 * <p>Load the Agent here (e.g. bind {@link com.openjiuwen.service.spec.spi.AgentHandler}), aligned with Python {@code @app.init}.
 */
public interface AgentInitHook {

    void onInit(AgentLifecycleContext context) throws Exception;
}
