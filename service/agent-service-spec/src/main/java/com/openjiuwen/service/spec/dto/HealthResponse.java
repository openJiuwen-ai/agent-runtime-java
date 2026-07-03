/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Lightweight health response aligned with Python AgentApp.
 *
 * @since 0.1.0
 */
@Data
public class HealthResponse {

    private String status;
    private String app;
    private String version;

    @JsonProperty("process_up")
    private boolean processUp;

    @JsonProperty("agent_loaded")
    private boolean agentLoaded;

    /** Default constructor for JSON deserialization. */
    public HealthResponse() {
    }

    /**
     * Creates a health response with all probe fields.
     *
     * @param status the health status
     * @param app the application name
     * @param version the application version
     * @param processUp whether the process is up
     * @param agentLoaded whether the agent is loaded
     */
    public HealthResponse(String status, String app, String version, boolean processUp, boolean agentLoaded) {
        this.status = status;
        this.app = app;
        this.version = version;
        this.processUp = processUp;
        this.agentLoaded = agentLoaded;
    }
}
