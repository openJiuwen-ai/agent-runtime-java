/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link TlsMaterialLoader}.
 */
class TlsMaterialLoaderTest {
    @Test
    void loadDecryptsPasswordsWithTlsScenes() {
        AtomicInteger keyScene = new AtomicInteger();
        AtomicInteger trustScene = new AtomicInteger();
        CredentialDecryptor decryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                if (sceneType == CredentialSceneType.TLS_KEYSTORE_PASSWORD) {
                    keyScene.set(sceneType);
                }
                if (sceneType == CredentialSceneType.TLS_TRUSTSTORE_PASSWORD) {
                    trustScene.set(sceneType);
                }
                return "plain-" + ciphertext;
            }
        };

        TlsMaterial material = TlsMaterialLoader.load("classpath:server.p12", "ENC(key)", "PKCS12",
            "classpath:truststore.p12", "ENC(trust)", "PKCS12", List.of("TLSv1.3"), true, decryptor);

        assertThat(material.keyStoreLocation()).isEqualTo("classpath:server.p12");
        assertThat(new String(material.keyStorePassword())).isEqualTo("plain-ENC(key)");
        assertThat(new String(material.trustStorePassword())).isEqualTo("plain-ENC(trust)");
        assertThat(material.enabledProtocols()).containsExactly("TLSv1.3");
        assertThat(keyScene).hasValue(CredentialSceneType.TLS_KEYSTORE_PASSWORD);
        assertThat(trustScene).hasValue(CredentialSceneType.TLS_TRUSTSTORE_PASSWORD);
    }
}
