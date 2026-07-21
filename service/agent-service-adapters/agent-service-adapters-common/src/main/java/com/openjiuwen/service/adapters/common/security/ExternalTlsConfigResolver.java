/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.spec.security.TlsMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves per-endpoint TLS configuration into {@link TlsMaterial}.
 *
 * @since 0.1.0
 */
public class ExternalTlsConfigResolver {
    private final GlobalTlsProperties globalTlsProperties;

    private final CredentialDecryptor credentialDecryptor;

    public ExternalTlsConfigResolver(GlobalTlsProperties globalTlsProperties, CredentialDecryptor credentialDecryptor) {
        this.globalTlsProperties = globalTlsProperties != null ? globalTlsProperties : new GlobalTlsProperties();
        this.credentialDecryptor = credentialDecryptor;
    }

    /**
     * Resolves TLS material when TLS is enabled for an endpoint.
     *
     * @param tlsConfig endpoint TLS configuration
     * @return resolved TLS material, or empty when TLS is disabled
     */
    public Optional<TlsMaterial> resolve(ExternalTlsConfig tlsConfig) {
        if (tlsConfig == null || !tlsConfig.isEnabled()) {
            return Optional.empty();
        }
        ResolvedTlsFields fields = mergeFields(tlsConfig);
        validateRequiredStores(fields);
        return Optional.of(TlsMaterialLoader.load(fields.keyStore(), fields.keyStorePassword(), fields.keyStoreType(),
            fields.trustStore(), fields.trustStorePassword(), fields.trustStoreType(), fields.enabledProtocols(),
            tlsConfig.isVerifyHostname(), credentialDecryptor));
    }

    private ResolvedTlsFields mergeFields(ExternalTlsConfig tlsConfig) {
        String ref = tlsConfig.getRef() == null ? "inline" : tlsConfig.getRef().trim().toLowerCase(Locale.ROOT);
        if ("global".equals(ref)) {
            if (!globalTlsProperties.isEnabled()) {
                throw new IllegalStateException(
                    "tls.ref=global requires openjiuwen.service.security.tls.enabled=true");
            }
            return new ResolvedTlsFields(firstNonBlank(tlsConfig.getKeyStore(), globalTlsProperties.getKeyStore()),
                firstNonBlank(tlsConfig.getKeyStorePassword(), globalTlsProperties.getKeyStorePassword()),
                firstNonBlank(tlsConfig.getKeyStoreType(), globalTlsProperties.getKeyStoreType()),
                firstNonBlank(tlsConfig.getTrustStore(), globalTlsProperties.getTrustStore()),
                firstNonBlank(tlsConfig.getTrustStorePassword(), globalTlsProperties.getTrustStorePassword()),
                firstNonBlank(tlsConfig.getTrustStoreType(), globalTlsProperties.getTrustStoreType()),
                mergeProtocols(tlsConfig.getEnabledProtocols(), globalTlsProperties.getEnabledProtocols()));
        }
        return new ResolvedTlsFields(tlsConfig.getKeyStore(), tlsConfig.getKeyStorePassword(),
            tlsConfig.getKeyStoreType(), tlsConfig.getTrustStore(), tlsConfig.getTrustStorePassword(),
            tlsConfig.getTrustStoreType(), mergeProtocols(tlsConfig.getEnabledProtocols(), List.of()));
    }

    private static void validateRequiredStores(ResolvedTlsFields fields) {
        if (!hasText(fields.trustStore()) && !hasText(fields.keyStore())) {
            throw new IllegalStateException("tls.enabled=true requires at least key-store or trust-store");
        }
    }

    private static List<String> mergeProtocols(List<String> inline, List<String> global) {
        if (inline != null && !inline.isEmpty()) {
            return List.copyOf(inline);
        }
        if (global != null && !global.isEmpty()) {
            return List.copyOf(global);
        }
        return List.of("TLSv1.2", "TLSv1.3");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResolvedTlsFields(String keyStore, String keyStorePassword, String keyStoreType, String trustStore,
        String trustStorePassword, String trustStoreType, List<String> enabledProtocols) {
        private ResolvedTlsFields {
            enabledProtocols = enabledProtocols == null ? new ArrayList<>() : List.copyOf(enabledProtocols);
        }
    }
}
