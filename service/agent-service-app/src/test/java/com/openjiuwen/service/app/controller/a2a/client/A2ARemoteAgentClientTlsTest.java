/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.JsonParser;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;
import com.openjiuwen.service.adapters.common.security.SslContextFactory;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.spec.security.TlsMaterial;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Verifies target trust and client certificates on actual A2A JSON-RPC/SSE connections.
 */
class A2ARemoteAgentClientTlsTest {
    private static TlsTestCertificates.Material certificates;

    private final AtomicInteger requests = new AtomicInteger();

    private final List<String> negotiatedProtocols = new CopyOnWriteArrayList<>();

    private HttpsServer server;

    private A2ARemoteAgentClient client;

    @BeforeAll
    static void generateCertificates() throws Exception {
        certificates = TlsTestCertificates.generate();
    }

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetTrustStoreAndClientCertificateReachActualTransport(boolean isMtls) throws Exception {
        String endpoint = startServer(isMtls);
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("trusted", A2ARemoteAgentClientSecurityTest.card(endpoint, null), 5, true, tls(isMtls));
        client = newClient(registry);

        A2ARemoteAgentClientSecurityTest.invoke(client, "trusted", false);
        A2ARemoteAgentClientSecurityTest.invoke(client, "trusted", true);

        assertThat(requests).hasValue(2);
    }

    @Test
    void trustStoreDoesNotLeakToAnotherTarget() throws Exception {
        String endpoint = startServer(false);
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("trusted", A2ARemoteAgentClientSecurityTest.card(endpoint, null), 5, false, tls(false));
        registry.register("untrusted", A2ARemoteAgentClientSecurityTest.card(endpoint, null), 5, false);
        client = newClient(registry);

        A2ARemoteAgentClientSecurityTest.invoke(client, "trusted", false);
        assertThatThrownBy(() -> A2ARemoteAgentClientSecurityTest.invoke(client, "untrusted", false))
                .hasStackTraceContaining("SSLHandshakeException");

        assertThat(requests).hasValue(1);
    }

    @Test
    void missingClientCertificateFailsBeforeHttp() throws Exception {
        String endpoint = startServer(true);
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("no-certificate", A2ARemoteAgentClientSecurityTest.card(endpoint, null),
                5, false, tls(false));
        client = newClient(registry);

        assertThatThrownBy(() -> A2ARemoteAgentClientSecurityTest.invoke(client, "no-certificate", false))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(org.a2aproject.sdk.spec.A2AClientException.class);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void configuredProtocolRestrictsActualHandshake(String protocol) throws Exception {
        String endpoint = startServer(false);
        ExternalTlsConfig config = tls(false);
        config.setEnabledProtocols(List.of(protocol));
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("restricted", A2ARemoteAgentClientSecurityTest.card(endpoint, null), 5, true, config);
        client = newClient(registry);

        A2ARemoteAgentClientSecurityTest.invoke(client, "restricted", false);
        A2ARemoteAgentClientSecurityTest.invoke(client, "restricted", true);

        assertThat(negotiatedProtocols).containsExactly(protocol, protocol);
    }

    @Test
    void incompatibleProtocolFailsBeforeHttp() throws Exception {
        String endpoint = startServer(false, "TLSv1.3");
        ExternalTlsConfig config = tls(false);
        config.setEnabledProtocols(List.of("TLSv1.2"));
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("incompatible", A2ARemoteAgentClientSecurityTest.card(endpoint, null), 5, false, config);
        client = newClient(registry);

        assertThatThrownBy(() -> A2ARemoteAgentClientSecurityTest.invoke(client, "incompatible", false))
                .hasStackTraceContaining("SSLHandshakeException");
        assertThat(requests).hasValue(0);
    }

    private A2ARemoteAgentClient newClient(A2ARemoteAgentCardRegistry registry) {
        return new A2ARemoteAgentClient(registry, 2,
                ExternalOutboundSecuritySupport.createDefault(value -> value));
    }

    private ExternalTlsConfig tls(boolean hasClientCertificate) {
        ExternalTlsConfig config = new ExternalTlsConfig();
        config.setEnabled(true);
        config.setTrustStore(certificates.clientTrustStoreLocation());
        config.setTrustStorePassword(TlsTestCertificates.PASSWORD);
        if (hasClientCertificate) {
            config.setKeyStore(certificates.clientKeyStoreLocation());
            config.setKeyStorePassword(TlsTestCertificates.PASSWORD);
        }
        return config;
    }

    private String startServer(boolean isMtls, String... protocols) throws Exception {
        char[] password = TlsTestCertificates.PASSWORD.toCharArray();
        TlsMaterial material = new TlsMaterial(certificates.serverKeyStoreLocation(), password, "PKCS12",
                certificates.serverTrustStoreLocation(), password, "PKCS12", List.of("TLSv1.3"), true);
        SSLContext sslContext = SslContextFactory.toSslContext(material, new DefaultResourceLoader());
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                if (protocols.length > 0) {
                    sslParameters.setProtocols(protocols);
                }
                sslParameters.setNeedClientAuth(isMtls);
                parameters.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/a2a", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                HttpsExchange httpsExchange = assertInstanceOf(HttpsExchange.class, exchange);
                negotiatedProtocols.add(httpsExchange.getSSLSession().getProtocol());
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                A2ARemoteAgentClientSecurityTest.writeResponse(exchange, request);
            }
        });
        server.start();
        return "https://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
    }
}
