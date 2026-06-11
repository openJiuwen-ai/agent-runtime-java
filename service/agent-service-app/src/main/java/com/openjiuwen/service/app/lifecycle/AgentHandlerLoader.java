package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;

/**
 * Built-in Agent loading for Agent Service (e.g. {@code openjiuwen.service.agent-id}).
 * <p>Not a business SPI — applications supply {@link com.openjiuwen.service.spec.spi.AgentHandler}
 * beans or configuration; this class performs framework-side loading into {@link AgentHandlerHolder}.
 */
public final class AgentHandlerLoader {

    private final ServiceProperties serviceProperties;

    public AgentHandlerLoader(ServiceProperties serviceProperties) {
        this.serviceProperties = serviceProperties;
    }

    /**
     * Loads the default handler into the holder when not already bound by a custom {@code AgentHandler} bean.
     */
    public void loadInto(AgentHandlerHolder holder, AgentLifecycleContext context) {
        if (holder.isLoaded()) {
            return;
        }
        String agentId = serviceProperties.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        holder.setHandler(new CoreAgentHandler(agentId));
    }
}
