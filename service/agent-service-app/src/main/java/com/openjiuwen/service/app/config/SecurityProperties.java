/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Security configuration for ingress TLS and fine-grained authorization.
 *
 * @since 0.1.0
 */
@Data
@ConfigurationProperties(prefix = "openjiuwen.service.security")
public class SecurityProperties {
    /** Master switch; when {@code false}, TLS binding and auth AOP are not registered. */
    private boolean enabled = false;

    private Tls tls = new Tls();

    private Auth auth = new Auth();

    /**
     * Ingress TLS / mTLS settings mapped to {@code server.ssl.*}.
     */
    @Data
    public static class Tls {
        private boolean enabled = false;

        private String protocol = "TLS";

        private List<String> enabledProtocols = new ArrayList<>(List.of("TLSv1.2", "TLSv1.3"));

        /** {@code none}, {@code want}, or {@code need}. */
        private String clientAuth = "none";

        private String keyStore;

        /** Ciphertext decrypted by {@code CredentialDecryptor} (scene {@code TLS_KEYSTORE_PASSWORD}). */
        private String keyStorePassword;

        private String keyStoreType = "PKCS12";

        private String trustStore;

        /** Ciphertext decrypted by {@code CredentialDecryptor} (scene {@code TLS_TRUSTSTORE_PASSWORD}). */
        private String trustStorePassword;

        private String trustStoreType = "PKCS12";

        /** {@code warn} or {@code fail}. */
        private String certificateExpiryPolicy = "warn";
    }

    /**
     * Fine-grained authorization switch.
     */
    @Data
    public static class Auth {
        private boolean enabled = false;
    }
}
