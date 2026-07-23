/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Global TLS settings bound from {@code openjiuwen.service.security.tls.*} for outbound
 * {@code tls.ref=global} resolution.
 *
 * @since 0.1.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openjiuwen.service.security.tls")
public class GlobalTlsProperties {
    private boolean enabled = false;

    private String keyStore;

    private String keyStorePassword;

    private String keyStoreType = "PKCS12";

    private String trustStore;

    private String trustStorePassword;

    private String trustStoreType = "PKCS12";

    private List<String> enabledProtocols = new ArrayList<>(List.of("TLSv1.2", "TLSv1.3"));

    /**
     * Assigns enabled TLS protocol names, defaulting to an empty modifiable list when null.
     *
     * @param enabledProtocols protocol names from YAML
     */
    public void setEnabledProtocols(List<String> enabledProtocols) {
        this.enabledProtocols = enabledProtocols != null ? enabledProtocols : new ArrayList<>();
    }
}
