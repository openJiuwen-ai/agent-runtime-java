/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.spec.security.ExternalAuthConfig;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-endpoint auth configuration bound from external service YAML.
 *
 * @since 0.1.0
 */
@Getter
@Setter
public class ExternalAuthProperties {
    private String type = "none";

    private String headerName = "Authorization";

    private String token;

    private String encryptedToken;

    private String credentialsRef;

    private Map<String, Object> extensions = new LinkedHashMap<>();

    /**
     * Assigns SPI extension map, defaulting to an empty modifiable map when null.
     *
     * @param extensions auth extension parameters from YAML
     */
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions != null ? extensions : new LinkedHashMap<>();
    }

    /**
     * Converts YAML-bound auth properties to the spec configuration record.
     *
     * @return spec auth configuration
     */
    public ExternalAuthConfig toSpecConfig() {
        return new ExternalAuthConfig(type, headerName, token, encryptedToken, credentialsRef, extensions);
    }
}
