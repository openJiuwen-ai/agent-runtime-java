/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * Unit tests for {@link ExternalHttpClientFactory}.
 */
class ExternalHttpClientFactoryTest {
    private static TlsMaterial tlsMaterial;

    @BeforeAll
    static void generateMaterial() throws Exception {
        String keyStoreLocation = OutboundTlsTestCertificates.generateServerKeyStore().toUri().toString();
        tlsMaterial = TlsMaterialLoader.load(keyStoreLocation, OutboundTlsTestCertificates.PASSWORD, "PKCS12", null,
            null, null, List.of("TLSv1.3"), true, passthroughDecryptor());
    }

    @Test
    void createJdkClientAppliesTlsOverlay() {
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(new DefaultResourceLoader());

        HttpClient client = factory.createJdkClient(tlsMaterial, Duration.ofSeconds(5));

        assertThat(client.sslContext()).isNotNull();
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void createOkHttpClientAppliesTlsAndAuthOverlays() {
        AuthMaterial auth = new AuthMaterial(Map.of("Authorization", "Bearer token-123"), Map.of(), Map.of());
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(new DefaultResourceLoader());

        OkHttpClient first = factory.createOkHttpClient(tlsMaterial, auth, Duration.ofSeconds(5));
        OkHttpClient second = factory.createOkHttpClient(tlsMaterial, auth, Duration.ofSeconds(5));

        assertThat(first).isNotSameAs(second);
        assertThat(first.sslSocketFactory()).isNotNull();
        assertThat(first.interceptors()).hasSize(1);
    }

    @Test
    void createClientsWithoutTlsUseDefaults() {
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(new DefaultResourceLoader());

        HttpClient jdkClient = factory.createJdkClient(null, Duration.ofSeconds(3));
        OkHttpClient okHttpClient = factory.createOkHttpClient(null, AuthMaterial.none(), Duration.ofSeconds(3));

        assertThat(jdkClient.sslContext()).isNotNull();
        assertThat(okHttpClient.interceptors()).isEmpty();
    }

    private static CredentialDecryptor passthroughDecryptor() {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                return ciphertext;
            }
        };
    }
}
