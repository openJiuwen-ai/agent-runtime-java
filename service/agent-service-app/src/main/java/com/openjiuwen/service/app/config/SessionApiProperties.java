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

    /** Whether the pause and resume endpoints are served. */
    private boolean pauseEnabled = true;

    /**
     * Whether the pause and resume endpoints are enabled.
     *
     * @return true when pause/resume are enabled
     */
    public boolean isPauseEnabled() {
        return pauseEnabled;
    }

    /**
     * Sets whether the pause and resume endpoints are enabled.
     *
     * @param pauseEnabled new enabled flag
     */
    public void setPauseEnabled(boolean pauseEnabled) {
        this.pauseEnabled = pauseEnabled;
    }
}
