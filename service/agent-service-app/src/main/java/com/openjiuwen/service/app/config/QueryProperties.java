/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query endpoint configuration.
 *
 * @since 0.1.0
 */
@Data
@ConfigurationProperties(prefix = "openjiuwen.service.query")
public class QueryProperties {
    private Endpoint webflux = new Endpoint();
    private boolean legacyPathEnabled = true;

    public void setWebflux(Endpoint webflux) {
        this.webflux = webflux != null ? webflux : new Endpoint();
    }

    /** WebFlux query endpoint settings. */
    @Data
    public static class Endpoint {
        private boolean enabled = false;
    }
}
