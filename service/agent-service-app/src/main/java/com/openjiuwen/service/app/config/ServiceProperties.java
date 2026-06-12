/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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

    /**
     * Version reported by the Agent Service health probe.
     */
    private String version = "0.1.0";

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
