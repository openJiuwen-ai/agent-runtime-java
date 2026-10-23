/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the remote catalog refresh loop.
 *
 * <p>Mapped from the {@code openjiuwen.service.a2a.remote-catalog} prefix. The
 * refresh TTL is the normal-state directory staleness bound: catalog changes
 * become visible to the caller within at most one TTL after the source
 * observes them.</p>
 *
 * @since 0.1.4
 */
@ConfigurationProperties(prefix = "openjiuwen.service.a2a.remote-catalog")
public class RemoteCatalogProperties {

    /** Default refresh TTL: five seconds. */
    private static final long DEFAULT_REFRESH_TTL_MS = 5000L;

    private long refreshTtlMs = DEFAULT_REFRESH_TTL_MS;

    /**
     * Returns the catalog refresh TTL in milliseconds.
     *
     * @return the refresh TTL in milliseconds
     */
    public long getRefreshTtlMs() {
        return refreshTtlMs;
    }

    /**
     * Sets the catalog refresh TTL in milliseconds.
     *
     * @param refreshTtlMs the refresh TTL in milliseconds
     */
    public void setRefreshTtlMs(long refreshTtlMs) {
        this.refreshTtlMs = refreshTtlMs;
    }
}
