/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the session management API surface (issue #117).
 *
 * @since 0.1.5
 */
@ConfigurationProperties(prefix = "openjiuwen.service.session-api")
public class SessionApiProperties {
    /**
     * Whether the session-operations endpoints (pause, resume, compact) are
     * served. Disabling the flag hides all three transitions behind a 404,
     * while the read-only endpoints (list, detail, messages) stay available.
     */
    private boolean isOperationsEnabled = true;

    /**
     * Whether the session-operations endpoints (pause, resume, compact) are
     * enabled.
     *
     * @return true when pause, resume, and compact are enabled
     */
    public boolean isOperationsEnabled() {
        return isOperationsEnabled;
    }

    /**
     * Sets whether the session-operations endpoints are enabled.
     *
     * @param isOperationsEnabled new enabled flag
     */
    public void setOperationsEnabled(boolean isOperationsEnabled) {
        this.isOperationsEnabled = isOperationsEnabled;
    }
}
