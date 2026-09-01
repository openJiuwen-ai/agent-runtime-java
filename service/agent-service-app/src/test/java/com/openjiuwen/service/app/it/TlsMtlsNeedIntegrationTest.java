/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.app.security.tls.TlsTestSslContextFactory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

/**
 * TC-TLS-02/03: mTLS {@code client-auth=need} ingress integration tests against
 * {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TlsMtlsNeedIntegrationTest {
    private static final TlsTestCertificates.Material TLS_MATERIAL;

    static {
        try {
            TLS_MATERIAL = TlsTestCertificates.generate();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerTlsProperties(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.security.enabled", () -> "true");
        registry.add("openjiuwen.service.security.tls.enabled", () -> "true");
        registry.add("openjiuwen.service.security.auth.enabled", () -> "false");
        registry.add("openjiuwen.service.security.tls.client-auth", () -> "need");
        registry.add("openjiuwen.service.security.tls.key-store", TLS_MATERIAL::serverKeyStoreLocation);
        registry.add("openjiuwen.service.security.tls.key-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.key-store-type", () -> "PKCS12");
        registry.add("openjiuwen.service.security.tls.trust-store", TLS_MATERIAL::serverTrustStoreLocation);
        registry.add("openjiuwen.service.security.tls.trust-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.trust-store-type", () -> "PKCS12");
    }

    @Test
    void mtlsNeedWithoutClientCertFailsHandshake() throws Exception {
        SSLContext sslContext = TlsTestSslContextFactory.clientTrustServer(TLS_MATERIAL);
        assertThatThrownBy(() -> TlsTestSslContextFactory.getHealthStatusCode(port, sslContext))
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Tag("smoke")
    @Test
    void mtlsNeedWithClientCertReturnsHealth200() throws Exception {
        SSLContext sslContext = TlsTestSslContextFactory.clientWithMtls(TLS_MATERIAL);
        assertThat(TlsTestSslContextFactory.getHealthStatusCode(port, sslContext)).isEqualTo(200);
    }
}
