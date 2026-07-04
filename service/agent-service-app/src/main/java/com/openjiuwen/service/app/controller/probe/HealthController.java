/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.probe;

import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.dto.HealthResponse;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AgentApp probe endpoint ({@code GET /health}).
 *
 * @since 0.1.0
 */
@RestController
@ConditionalOnWebApplication
public class HealthController {
    private static final String STATUS_HEALTHY = "healthy";
    private static final String DEFAULT_APP = "agent-service";

    private final AgentReadiness readiness;
    private final AgentServiceIdentity identity;
    private final ServiceProperties serviceProperties;

    public HealthController(AgentReadiness readiness, AgentServiceIdentity identity,
            ServiceProperties serviceProperties) {
        this.readiness = readiness;
        this.identity = identity;
        this.serviceProperties = serviceProperties;
    }

    /**
     * Returns the health probe payload.
     *
     * @return the health response
     */
    @GetMapping(AgentServicePaths.HEALTH)
    public HealthResponse health() {
        return new HealthResponse(STATUS_HEALTHY, app(), version(), readiness.isProcessUp(), readiness.isAgentLoaded());
    }

    private String app() {
        String appName = identity.getAppName();
        if (appName != null && !appName.isBlank()) {
            return appName;
        }
        return DEFAULT_APP;
    }

    private String version() {
        return serviceProperties.getVersion();
    }
}
