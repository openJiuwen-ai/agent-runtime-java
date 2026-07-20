/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security.tls;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds {@link SSLContext} instances for TLS integration tests.
 *
 * @since 0.1.0
 */
public final class TlsTestSslContextFactory {
    private TlsTestSslContextFactory() {
    }

    /**
     * Creates a client SSL context that trusts the test server certificate only.
     *
     * @param material generated TLS material
     * @return SSL context without client authentication
     * @throws Exception if the context cannot be built
     */
    public static SSLContext clientTrustServer(TlsTestCertificates.Material material) throws Exception {
        return build(material.clientTrustStore(), null);
    }

    /**
     * Creates a client SSL context with a client certificate for mTLS.
     *
     * @param material generated TLS material
     * @return SSL context with client authentication
     * @throws Exception if the context cannot be built
     */
    public static SSLContext clientWithMtls(TlsTestCertificates.Material material) throws Exception {
        return build(material.clientTrustStore(), material.clientKeyStore());
    }

    /**
     * Performs an HTTPS GET against {@code /health} and returns the status code.
     *
     * @param port server port
     * @param sslContext client SSL context
     * @return HTTP status code
     * @throws IOException if the request fails before a response is received
     */
    public static int getHealthStatusCode(int port, SSLContext sslContext) throws IOException {
        URL url = new URL("https://127.0.0.1:" + port + "/health");
        URLConnection connection = url.openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection) {
            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
            httpsConnection.setConnectTimeout(5_000);
            httpsConnection.setReadTimeout(5_000);
            httpsConnection.setRequestMethod("GET");
            try {
                return httpsConnection.getResponseCode();
            } finally {
                httpsConnection.disconnect();
            }
        } else {
            throw new IOException("Failed to open HTTPS connection");
        }
    }

    private static SSLContext build(java.nio.file.Path trustStorePath, java.nio.file.Path keyStorePath)
            throws Exception {
        char[] password = TlsTestCertificates.PASSWORD.toCharArray();
        TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = loadKeyStore(trustStorePath, password);
        trustManagerFactory.init(trustStore);

        KeyManagerFactory keyManagerFactory = null;
        if (keyStorePath != null) {
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(loadKeyStore(keyStorePath, password), password);
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
                trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    private static KeyStore loadKeyStore(java.nio.file.Path path, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = Files.newInputStream(path)) {
            keyStore.load(inputStream, password);
        }
        return keyStore;
    }
}