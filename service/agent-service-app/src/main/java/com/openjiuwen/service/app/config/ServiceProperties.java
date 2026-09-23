/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Service runtime configuration.
 *
 * @since 2026-07-03
 */
@Data
@ConfigurationProperties(prefix = "openjiuwen.service")
public class ServiceProperties {
    /**
     * Agent id registered in {@code Runner.resourceMgr()} for the default
     * {@code JiuwenCoreAgentHandler}.
     */
    private String agentId;

    /** Controls only Runtime-owned HTTP ingress; never the host Web server. */
    private Http http = new Http();

    /** Startup-time HTTP ingress configuration. */
    @Data
    public static class Http {
        private boolean enabled = true;
    }

    /**
     * Version reported by the Agent Service health probe.
     */
    private String version = "0.1.1.post1";
}
