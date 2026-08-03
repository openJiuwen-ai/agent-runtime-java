/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;
import com.openjiuwen.service.app.it.support.AgentReadinessTestSupport;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.app.security.tls.TlsTestSslContextFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;

/**
 * TC-TLS-AUTH-01: combined HTTPS + fine-grained authorization integration test.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IngressAuthorizationTestSupport.AllowQueryAuthorizerConfig.class)
class TlsAuthCombinedIntegrationTest {
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
    private DefaultAgentReadiness readiness;

    @Autowired
    private ObjectProvider<AgentHandler> agentHandlerProvider;

    @BeforeEach
    void ensureAgentLoaded() {
        AgentReadinessTestSupport.ensureAgentLoaded(readiness, agentHandlerProvider);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("openjiuwen.service.security.enabled", () -> "true");
        registry.add("openjiuwen.service.security.tls.enabled", () -> "true");
        registry.add("openjiuwen.service.security.auth.enabled", () -> "true");
        registry.add("openjiuwen.service.security.tls.client-auth", () -> "none");
        registry.add("openjiuwen.service.security.tls.key-store", TLS_MATERIAL::serverKeyStoreLocation);
        registry.add("openjiuwen.service.security.tls.key-store-password", () -> TlsTestCertificates.PASSWORD);
        registry.add("openjiuwen.service.security.tls.key-store-type", () -> "PKCS12");
    }

    @Tag("smoke")
    @Test
    void httpsQueryWithAuthorizationAllowReturns200() throws Exception {
        SSLContext sslContext = TlsTestSslContextFactory.clientTrustServer(TLS_MATERIAL);
        String body = """
            {"conversation_id":"conv-tls-auth","messages":[{"role":"user","content":"hello"}],"stream":false}
            """;
        int statusCode = TlsTestSslContextFactory.postJsonStatusCode(port, sslContext, "/v1/query", body);
        assertThat(statusCode).isEqualTo(200);
    }
}
