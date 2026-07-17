/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.app.config.SecurityProperties;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Enumeration;

/**
 * Validates TLS material and keystore certificate expiry at startup.
 *
 * @since 0.1.0
 */
public final class TlsStartupValidator {
    private TlsStartupValidator() {
    }

    /**
     * Validates TLS configuration before the web server starts.
     *
     * @param tlsProperties TLS configuration
     * @param material loaded TLS material
     * @param resourceLoader resource loader for store locations
     */
    public static void validate(SecurityProperties.Tls tlsProperties, TlsMaterial material,
        ResourceLoader resourceLoader) {
        requireText(tlsProperties.getKeyStore(), "openjiuwen.service.security.tls.key-store");
        assertResourceReadable(tlsProperties.getKeyStore(), resourceLoader, "key-store");
        String clientAuth = normalizeClientAuth(tlsProperties.getClientAuth());
        if ("want".equals(clientAuth) || "need".equals(clientAuth)) {
            requireText(tlsProperties.getTrustStore(), "openjiuwen.service.security.tls.trust-store");
            assertResourceReadable(tlsProperties.getTrustStore(), resourceLoader, "trust-store");
        }
        validateCertificateExpiry(tlsProperties, material, resourceLoader);
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured when tls.enabled=true");
        }
    }

    private static void assertResourceReadable(String location, ResourceLoader resourceLoader, String label) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("TLS " + label + " not found at location: " + location);
        }
    }

    private static String normalizeClientAuth(String clientAuth) {
        return clientAuth == null ? "none" : clientAuth.trim().toLowerCase();
    }

    private static void validateCertificateExpiry(SecurityProperties.Tls tlsProperties, TlsMaterial material,
        ResourceLoader resourceLoader) {
        if (material.keyStoreLocation() == null || material.keyStoreLocation().isBlank()) {
            return;
        }
        try {
            Instant earliestExpiry = loadEarliestExpiry(resourceLoader.getResource(material.keyStoreLocation()),
                material.keyStoreType(), material.keyStorePassword());
            if (earliestExpiry != null && earliestExpiry.isBefore(Instant.now())) {
                String message = "TLS key-store certificate expired at " + earliestExpiry;
                if ("fail".equalsIgnoreCase(tlsProperties.getCertificateExpiryPolicy())) {
                    throw new IllegalStateException(message);
                }
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to inspect TLS key-store certificates", ex);
        }
    }

    private static Instant loadEarliestExpiry(Resource resource, String storeType, char[] password)
        throws Exception {
        KeyStore keyStore = KeyStore.getInstance(storeType == null || storeType.isBlank() ? "PKCS12" : storeType);
        try (InputStream inputStream = resource.getInputStream()) {
            keyStore.load(inputStream, password == null ? new char[0] : password);
        }
        Instant earliest = null;
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) {
                Instant notAfter = x509.getNotAfter().toInstant();
                earliest = earliest == null || notAfter.isBefore(earliest) ? notAfter : earliest;
            }
        }
        return earliest;
    }
}
