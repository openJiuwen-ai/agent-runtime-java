/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.app.security.tls.TlsTestSslContextFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;

/**
 * TC-TLS-04: optional mTLS {@code client-auth=want} still allows clients without
 * a certificate against {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TlsMtlsWantIntegrationTest {
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
        registry.add("openjiuwen.service.security.tls.client-auth", () -> "want");
        registry.add("openjiuwen.service.security.tls.key-store", TLS_MATERIAL::serverKeyStoreLocation);
        registry.add("openjiuwen.service.security.tls.key-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.key-store-type", () -> "PKCS12");
        registry.add("openjiuwen.service.security.tls.trust-store", TLS_MATERIAL::serverTrustStoreLocation);
        registry.add("openjiuwen.service.security.tls.trust-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.trust-store-type", () -> "PKCS12");
    }

    @Test
    void mtlsWantWithoutClientCertStillReturnsHealth200() throws Exception {
        SSLContext sslContext = TlsTestSslContextFactory.clientTrustServer(TLS_MATERIAL);
        assertThat(TlsTestSslContextFactory.getHealthStatusCode(port, sslContext)).isEqualTo(200);
    }
}
