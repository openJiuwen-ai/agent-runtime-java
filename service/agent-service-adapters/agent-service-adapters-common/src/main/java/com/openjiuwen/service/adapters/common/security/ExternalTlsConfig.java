/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-endpoint TLS configuration bound from external service YAML.
 *
 * @since 0.1.0
 */
@Getter
@Setter
public class ExternalTlsConfig {
    private boolean enabled = false;

    /** {@code global} or {@code inline}. */
    private String ref = "inline";

    private String keyStore;

    private String keyStorePassword;

    private String keyStoreType;

    private String trustStore;

    private String trustStorePassword;

    private String trustStoreType;

    private List<String> enabledProtocols = new ArrayList<>();

    private boolean verifyHostname = true;

    /**
     * Assigns enabled TLS protocol names, defaulting to an empty modifiable list when null.
     *
     * @param enabledProtocols protocol names from YAML
     */
    public void setEnabledProtocols(List<String> enabledProtocols) {
        this.enabledProtocols = enabledProtocols != null ? enabledProtocols : new ArrayList<>();
    }
}
