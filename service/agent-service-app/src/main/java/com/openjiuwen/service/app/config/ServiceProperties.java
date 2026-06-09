package com.openjiuwen.service.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Service runtime configuration.
 */
@ConfigurationProperties(prefix = "openjiuwen.service")
public class ServiceProperties {

    /**
     * Agent id registered in {@code Runner.resourceMgr()} for the default {@code CoreAgentHandler}.
     */
    private String agentId;

    private boolean autoStartRunner = true;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public boolean isAutoStartRunner() {
        return autoStartRunner;
    }

    public void setAutoStartRunner(boolean autoStartRunner) {
        this.autoStartRunner = autoStartRunner;
    }
}
