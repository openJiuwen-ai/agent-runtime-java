/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import okhttp3.OkHttpClient;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds outbound HTTP clients with optional TLS and auth overlays.
 *
 * @since 0.1.0
 */
public class ExternalHttpClientFactory {
    private final ResourceLoader resourceLoader;

    public ExternalHttpClientFactory(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader != null ? resourceLoader : new DefaultResourceLoader();
    }

    /**
     * Creates a JDK HTTP client for MCP / remote transports.
     *
     * @param tls optional TLS material
     * @param connectTimeout connect timeout
     * @return configured HTTP client
     */
    public HttpClient createJdkClient(com.openjiuwen.service.spec.security.TlsMaterial tls, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()) {
            builder.connectTimeout(connectTimeout);
        }
        if (tls != null) {
            SSLContext sslContext = SslContextFactory.toSslContext(tls, resourceLoader);
            builder.sslContext(sslContext);
            if (!tls.verifyHostname()) {
                SSLParameters sslParameters = new SSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm(null);
                builder.sslParameters(sslParameters);
            }
        }
        return builder.build();
    }

    /**
     * Creates an OkHttp client for sandbox transports.
     *
     * @param tls optional TLS material
     * @param auth optional auth overlay
     * @param connectTimeout connect timeout
     * @return configured OkHttp client
     */
    public OkHttpClient createOkHttpClient(com.openjiuwen.service.spec.security.TlsMaterial tls,
        com.openjiuwen.service.spec.security.AuthMaterial auth, Duration connectTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        if (connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()) {
            long millis = connectTimeout.toMillis();
            builder.connectTimeout(millis, TimeUnit.MILLISECONDS)
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS);
        }
        if (tls != null) {
            SSLContext sslContext = SslContextFactory.toSslContext(tls, resourceLoader);
            X509TrustManager trustManager = SslContextFactory.toTrustManager(tls, resourceLoader);
            builder.sslSocketFactory(sslContext.getSocketFactory(),
                trustManager != null ? trustManager : platformTrustManager());
            if (!tls.verifyHostname()) {
                builder.hostnameVerifier((hostname, session) -> true);
            }
        }
        if (auth != null && !auth.isEmpty()) {
            builder.addInterceptor(chain -> {
                okhttp3.Request.Builder requestBuilder = chain.request().newBuilder();
                auth.headers().forEach(requestBuilder::addHeader);
                return chain.proceed(requestBuilder.build());
            });
        }
        return builder.build();
    }

    private static X509TrustManager platformTrustManager() {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((java.security.KeyStore) null);
            for (javax.net.ssl.TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509TrustManager) {
                    return x509TrustManager;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to obtain platform trust manager", ex);
        }
        throw new IllegalStateException("No platform X509TrustManager available");
    }
}
