/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.RecordingFineGrainedAuthorizer;
import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.app.security.tls.TlsTestSslContextFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

/**
 * TC-TLS-04: TLS handshake failures do not reach authorization AOP.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IngressAuthorizationTestSupport.AllowQueryAuthorizerConfig.class)
class TlsHandshakeIsolationIntegrationTest {
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

    @Autowired
    private RecordingFineGrainedAuthorizer recordingAuthorizer;

    @DynamicPropertySource
    static void registerTlsProperties(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.security.enabled", () -> "true");
        registry.add("openjiuwen.service.security.tls.enabled", () -> "true");
        registry.add("openjiuwen.service.security.auth.enabled", () -> "true");
        registry.add("openjiuwen.service.security.tls.client-auth", () -> "need");
        registry.add("openjiuwen.service.security.tls.key-store", TLS_MATERIAL::serverKeyStoreLocation);
        registry.add("openjiuwen.service.security.tls.key-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.key-store-type", () -> "PKCS12");
        registry.add("openjiuwen.service.security.tls.trust-store", TLS_MATERIAL::serverTrustStoreLocation);
        registry.add("openjiuwen.service.security.tls.trust-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.trust-store-type", () -> "PKCS12");
    }

    @Test
    void mtlsHandshakeFailureDoesNotInvokeAuthorizationSpi() throws Exception {
        recordingAuthorizer.clear();
        SSLContext sslContext = TlsTestSslContextFactory.clientTrustServer(TLS_MATERIAL);
        assertThatThrownBy(() -> TlsTestSslContextFactory.postJsonStatusCode(port, sslContext, "/v1/query", "{}"))
            .isInstanceOf(SSLHandshakeException.class);
        assertThat(recordingAuthorizer.requests()).isEmpty();
    }
}
