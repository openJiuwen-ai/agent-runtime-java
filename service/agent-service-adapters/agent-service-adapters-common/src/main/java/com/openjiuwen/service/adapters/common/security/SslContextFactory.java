/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.spec.security.TlsMaterial;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds {@link SSLContext} instances from {@link TlsMaterial}.
 *
 * @since 0.1.0
 */
public final class SslContextFactory {
    private SslContextFactory() {
    }

    /**
     * Converts TLS material into an {@link SSLContext}.
     *
     * @param material TLS material
     * @param resourceLoader resource loader for store locations
     * @return SSL context
     */
    public static SSLContext toSslContext(TlsMaterial material, ResourceLoader resourceLoader) {
        if (material == null) {
            throw new IllegalArgumentException("TLS material must not be null");
        }
        try {
            TrustManagerFactory trustManagerFactory = null;
            if (hasText(material.trustStoreLocation())) {
                KeyStore trustStore = loadKeyStore(resourceLoader, material.trustStoreLocation(),
                    material.trustStoreType(), material.trustStorePassword());
                trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(trustStore);
            }

            KeyManagerFactory keyManagerFactory = null;
            if (hasText(material.keyStoreLocation())) {
                KeyStore keyStore = loadKeyStore(resourceLoader, material.keyStoreLocation(), material.keyStoreType(),
                    material.keyStorePassword());
                keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, material.keyStorePassword());
            }

            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            sslContext.init(keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
                trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build outbound SSLContext", ex);
        }
    }

    /**
     * Extracts the first {@link X509TrustManager} from TLS material.
     *
     * @param material TLS material
     * @param resourceLoader resource loader for store locations
     * @return trust manager, or an empty optional when no trust store is configured
     */
    public static Optional<X509TrustManager> toTrustManager(TlsMaterial material, ResourceLoader resourceLoader) {
        if (material == null || !hasText(material.trustStoreLocation())) {
            return Optional.empty();
        }
        try {
            KeyStore trustStore = loadKeyStore(resourceLoader, material.trustStoreLocation(),
                material.trustStoreType(), material.trustStorePassword());
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            for (javax.net.ssl.TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509TrustManager) {
                    return Optional.of(x509TrustManager);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build outbound trust manager", ex);
        }
        throw new IllegalStateException("No X509TrustManager available for outbound TLS");
    }

    private static KeyStore loadKeyStore(ResourceLoader resourceLoader, String location, String storeType,
        char[] password) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("TLS store not found at location: " + location);
        }
        KeyStore keyStore = KeyStore.getInstance(defaultStoreType(storeType));
        try (InputStream inputStream = resource.getInputStream()) {
            keyStore.load(inputStream, password == null ? new char[0] : password);
        }
        return keyStore;
    }

    private static String defaultStoreType(String storeType) {
        return storeType == null || storeType.isBlank() ? "PKCS12" : storeType;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
