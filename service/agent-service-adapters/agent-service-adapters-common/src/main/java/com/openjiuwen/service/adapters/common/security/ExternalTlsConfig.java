/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

/**
 * Per-endpoint TLS configuration bound from external service YAML.
 *
 * @since 0.1.0
 */
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

    private java.util.List<String> enabledProtocols = new java.util.ArrayList<>();

    private boolean verifyHostname = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public java.util.List<String> getEnabledProtocols() {
        return enabledProtocols;
    }

    public void setEnabledProtocols(java.util.List<String> enabledProtocols) {
        this.enabledProtocols = enabledProtocols != null ? enabledProtocols : new java.util.ArrayList<>();
    }

    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    public void setVerifyHostname(boolean verifyHostname) {
        this.verifyHostname = verifyHostname;
    }
}
