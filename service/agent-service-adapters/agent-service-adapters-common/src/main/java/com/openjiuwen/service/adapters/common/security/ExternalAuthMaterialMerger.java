/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.ExternalAuthConfig;
import com.openjiuwen.service.spec.security.ExternalAuthenticationException;
import com.openjiuwen.service.spec.security.ExternalAuthenticator;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Merges built-in and SPI outbound authentication overlays.
 *
 * @since 0.1.0
 */
public class ExternalAuthMaterialMerger {
    private final ExternalAuthenticator authenticator;

    private final CredentialDecryptor credentialDecryptor;

    public ExternalAuthMaterialMerger(ExternalAuthenticator authenticator, CredentialDecryptor credentialDecryptor) {
        this.authenticator = authenticator != null ? authenticator : new NoOpExternalAuthenticator();
        this.credentialDecryptor = credentialDecryptor;
    }

    /**
     * Merges built-in and SPI auth material for an outbound target.
     *
     * @param target outbound target
     * @param authConfig auth configuration
     * @return merged auth material
     */
    public AuthMaterial merge(ExternalTargetRef target, ExternalAuthConfig authConfig) {
        ExternalAuthConfig effectiveConfig = authConfig != null ? authConfig : ExternalAuthConfig.none();
        AuthMaterial merged = buildBuiltinMaterial(target, effectiveConfig);
        AuthMaterial spiMaterial = authenticator.authenticate(target, effectiveConfig);
        merged = mergeHttpMaterial(merged, spiMaterial);
        if (!effectiveConfig.isNoneType() && merged.isEmpty()) {
            throw new ExternalAuthenticationException(
                "auth.type=" + effectiveConfig.type() + " produced empty authentication material for target "
                    + target.targetId());
        }
        return merged;
    }

    private AuthMaterial buildBuiltinMaterial(ExternalTargetRef target, ExternalAuthConfig authConfig) {
        String type = authConfig.type().toLowerCase(Locale.ROOT);
        if ("none".equals(type) || "custom".equals(type)) {
            return AuthMaterial.none();
        }
        String token = resolveToken(authConfig, target.credentialSceneType());
        if (token == null || token.isBlank()) {
            return AuthMaterial.none();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        if ("bearer".equals(type)) {
            headers.put("Authorization", "Bearer " + token);
            return new AuthMaterial(headers, queryParams, Map.of());
        }
        if ("header".equals(type)) {
            headers.put(authConfig.headerName(), token);
            return new AuthMaterial(headers, queryParams, Map.of());
        }
        return AuthMaterial.none();
    }

    private String resolveToken(ExternalAuthConfig authConfig, int sceneType) {
        if (authConfig.encryptedToken() != null && !authConfig.encryptedToken().isBlank()) {
            return credentialDecryptor.decrypt(authConfig.encryptedToken(), sceneType);
        }
        return authConfig.token();
    }

    private static AuthMaterial mergeHttpMaterial(AuthMaterial base, AuthMaterial overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return base == null ? AuthMaterial.none() : base;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (base != null) {
            headers.putAll(base.headers());
        }
        headers.putAll(overlay.headers());

        Map<String, String> queryParams = new LinkedHashMap<>();
        if (base != null) {
            queryParams.putAll(base.queryParams());
        }
        queryParams.putAll(overlay.queryParams());

        Map<String, Object> materialExtensions = overlay.materialExtensions();
        return new AuthMaterial(headers, queryParams, materialExtensions);
    }
}
