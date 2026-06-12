/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentApp lifecycle configuration (Issue #5).
 */
@ConfigurationProperties(prefix = "openjiuwen.service.lifecycle")
public class LifecycleProperties {

    private long shutdownTimeoutMs = 30000L;

    private boolean initFailFast = true;

    public long getShutdownTimeoutMs() {
        return shutdownTimeoutMs;
    }

    public void setShutdownTimeoutMs(long shutdownTimeoutMs) {
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    public boolean isInitFailFast() {
        return initFailFast;
    }

    public void setInitFailFast(boolean initFailFast) {
        this.initFailFast = initFailFast;
    }
}
