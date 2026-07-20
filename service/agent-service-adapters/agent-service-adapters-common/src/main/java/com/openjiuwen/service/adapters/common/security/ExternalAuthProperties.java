/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.spec.security.ExternalAuthConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-endpoint auth configuration bound from external service YAML.
 *
 * @since 0.1.0
 */
public class ExternalAuthProperties {
    private String type = "none";

    private String headerName = "Authorization";

    private String token;

    private String encryptedToken;

    private String credentialsRef;

    private Map<String, Object> extensions = new LinkedHashMap<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public void setEncryptedToken(String encryptedToken) {
        this.encryptedToken = encryptedToken;
    }

    public String getCredentialsRef() {
        return credentialsRef;
    }

    public void setCredentialsRef(String credentialsRef) {
        this.credentialsRef = credentialsRef;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

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
