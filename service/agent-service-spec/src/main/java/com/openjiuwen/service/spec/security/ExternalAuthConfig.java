/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound authentication configuration bound from YAML and passed to
 * {@link ExternalAuthenticator}.
 *
 * @param type authentication type ({@code none}, {@code bearer}, {@code header}, {@code custom})
 * @param headerName header name for {@code header} type
 * @param token plain token when not encrypted
 * @param encryptedToken ciphertext decrypted by {@code CredentialDecryptor}
 * @param credentialsRef optional credentials reference for custom SPI
 * @param extensions YAML {@code auth.extensions} passed to SPI as input
 * @since 0.1.0
 */
public record ExternalAuthConfig(String type, String headerName, String token, String encryptedToken,
    String credentialsRef, Map<String, Object> extensions) {

    /**
     * Default no-authentication configuration.
     *
     * @return ExternalAuthConfig
     */
    public static ExternalAuthConfig none() {
        return new ExternalAuthConfig("none", "Authorization", null, null, null, Map.of());
    }

    /**
     * Normalizes null fields to safe defaults.
     */
    public ExternalAuthConfig {
        if (type == null || type.isBlank()) {
            type = "none";
        } else {
            type = type.trim().toLowerCase();
        }
        if (headerName == null || headerName.isBlank()) {
            headerName = "Authorization";
        }
        if (extensions == null || extensions.isEmpty()) {
            extensions = Map.of();
        } else {
            extensions = Map.copyOf(new LinkedHashMap<>(extensions));
        }
    }

    /**
     * Whether this configuration explicitly disables outbound authentication.
     *
     * @return boolean
     */
    public boolean isNoneType() {
        return "none".equals(type);
    }
}
