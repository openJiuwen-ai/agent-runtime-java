/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link SslContextFactory}.
 */
class SslContextFactoryTest {
    private static String keyStoreLocation;

    @BeforeAll
    static void generateMaterial() throws Exception {
        Path keyStore = OutboundTlsTestCertificates.generateServerKeyStore();
        keyStoreLocation = keyStore.toUri().toString();
    }

    @Test
    void buildsSslContextFromGeneratedKeystore() {
        TlsMaterial material = TlsMaterialLoader.load(keyStoreLocation, OutboundTlsTestCertificates.PASSWORD, "PKCS12",
            null, null, null, List.of("TLSv1.3"), true, passthroughDecryptor());

        assertThat(SslContextFactory.toSslContext(material, new DefaultResourceLoader())).isNotNull();
        assertThat(SslContextFactory.toTrustManager(material, new DefaultResourceLoader())).isEmpty();
    }

    @Test
    void missingKeystoreLocationFailsFast() {
        TlsMaterial material = TlsMaterialLoader.load("classpath:missing-store.p12",
            OutboundTlsTestCertificates.PASSWORD, "PKCS12", null, null, null, List.of("TLSv1.3"), true,
            passthroughDecryptor());

        assertThatThrownBy(() -> SslContextFactory.toSslContext(material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to build outbound SSLContext")
            .cause()
            .hasMessageContaining("TLS store not found");
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
