/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openjiuwen.service.versatile")
public class VersatileProperties {

    /**
     * Base URL of the remote Versatile service (e.g. {@code https://versatile.example.com}).
     */
    private String baseUrl;

    /**
     * REST path for unary query (appended to {@link #baseUrl}).
     */
    private String queryPath = "/v1/query";

    /**
     * REST path for streaming query when {@code stream=true}.
     */
    private String streamPath = "/v1/query/stream";

    /**
     * Optional workflow or skill id exposed by Versatile for this deployment.
     */
    private String workflowId;

    private int connectTimeoutMs = 5000;

    private int readTimeoutMs = 120000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getQueryPath() {
        return queryPath;
    }

    public void setQueryPath(String queryPath) {
        this.queryPath = queryPath;
    }

    public String getStreamPath() {
        return streamPath;
    }

    public void setStreamPath(String streamPath) {
        this.streamPath = streamPath;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
