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

import java.util.List;

/**
 * Unit tests for {@link ExternalTlsConfigResolver}.
 */
class ExternalTlsConfigResolverTest {
    private static String inlineKeyStoreLocation;

    @BeforeAll
    static void generateMaterial() throws Exception {
        inlineKeyStoreLocation = OutboundTlsTestCertificates.generateServerKeyStore().toUri().toString();
    }

    @Test
    void globalRefRequiresEnabledGlobalTls() {
        ExternalTlsConfig tlsConfig = new ExternalTlsConfig();
        tlsConfig.setEnabled(true);
        tlsConfig.setRef("global");
        ExternalTlsConfigResolver resolver = new ExternalTlsConfigResolver(new GlobalTlsProperties(),
            passthroughDecryptor());

        assertThatThrownBy(() -> resolver.resolve(tlsConfig)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tls.ref=global");
    }

    @Test
    void inlineOverrideWinsOverGlobalFields() {
        GlobalTlsProperties global = new GlobalTlsProperties();
        global.setEnabled(true);
        global.setKeyStore("classpath:global.p12");
        global.setKeyStorePassword("ENC(global-key)");
        global.setTrustStore("classpath:global-trust.p12");
        global.setTrustStorePassword("ENC(global-trust)");
        global.setEnabledProtocols(List.of("TLSv1.3"));

        ExternalTlsConfig inline = new ExternalTlsConfig();
        inline.setEnabled(true);
        inline.setRef("global");
        inline.setTrustStore("classpath:inline-trust.p12");
        inline.setTrustStorePassword("ENC(inline-trust)");

        ExternalTlsConfigResolver resolver = new ExternalTlsConfigResolver(global, passthroughDecryptor());
        TlsMaterial material = resolver.resolve(inline).orElseThrow();

        assertThat(material.keyStoreLocation()).isEqualTo("classpath:global.p12");
        assertThat(material.trustStoreLocation()).isEqualTo("classpath:inline-trust.p12");
        assertThat(new String(material.trustStorePassword())).isEqualTo("plain-ENC(inline-trust)");
    }

    @Test
    void disabledTlsReturnsEmpty() {
        ExternalTlsConfigResolver resolver = new ExternalTlsConfigResolver(new GlobalTlsProperties(),
            passthroughDecryptor());

        assertThat(resolver.resolve(new ExternalTlsConfig())).isEmpty();
    }

    @Test
    void inlineTlsWorksWhenGlobalTlsDisabled() {
        GlobalTlsProperties global = new GlobalTlsProperties();
        global.setEnabled(false);

        ExternalTlsConfig inline = new ExternalTlsConfig();
        inline.setEnabled(true);
        inline.setRef("inline");
        inline.setKeyStore(keyStoreLocation());
        inline.setKeyStorePassword(OutboundTlsTestCertificates.PASSWORD);
        inline.setKeyStoreType("PKCS12");

        ExternalTlsConfigResolver resolver = new ExternalTlsConfigResolver(global, passthroughDecryptor());

        assertThat(resolver.resolve(inline)).isPresent();
    }

    private static String keyStoreLocation() {
        return inlineKeyStoreLocation;
    }

    private static CredentialDecryptor passthroughDecryptor() {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                return "plain-" + ciphertext;
            }
        };
    }
}
