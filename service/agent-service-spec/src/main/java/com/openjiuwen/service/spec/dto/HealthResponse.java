/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight health response aligned with Python AgentApp.
 */
public class HealthResponse {

    private String status;
    private String app;
    private String version;

    @JsonProperty("process_up")
    private boolean processUp;

    @JsonProperty("agent_loaded")
    private boolean agentLoaded;

    public HealthResponse() {
    }

    public HealthResponse(String status, String app, String version,
                          boolean processUp, boolean agentLoaded) {
        this.status = status;
        this.app = app;
        this.version = version;
        this.processUp = processUp;
        this.agentLoaded = agentLoaded;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @JsonProperty("process_up")
    public boolean isProcessUp() {
        return processUp;
    }

    public void setProcessUp(boolean processUp) {
        this.processUp = processUp;
    }

    @JsonProperty("agent_loaded")
    public boolean isAgentLoaded() {
        return agentLoaded;
    }

    public void setAgentLoaded(boolean agentLoaded) {
        this.agentLoaded = agentLoaded;
    }
}
