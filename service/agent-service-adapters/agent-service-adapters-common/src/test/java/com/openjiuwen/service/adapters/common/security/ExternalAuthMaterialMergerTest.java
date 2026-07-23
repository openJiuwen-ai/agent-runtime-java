/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.ExternalAuthConfig;
import com.openjiuwen.service.spec.security.ExternalAuthenticationException;
import com.openjiuwen.service.spec.security.ExternalAuthenticator;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link ExternalAuthMaterialMerger}.
 */
class ExternalAuthMaterialMergerTest {
    private final ExternalTargetRef target = new ExternalTargetRef("MCP", "srv-1", "https://mcp.internal/sse",
        CredentialSceneType.MCP_AUTH_TOKEN);

    @Test
    void mergeBearerTokenFromEncryptedValue() {
        AtomicInteger scene = new AtomicInteger();
        CredentialDecryptor decryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                scene.set(sceneType);
                return "secret-token";
            }
        };
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(), decryptor);

        AuthMaterial material = merger.merge(target,
            new ExternalAuthConfig("bearer", "Authorization", null, "ENC(token)", null, Map.of()));

        assertThat(material.headers()).containsEntry("Authorization", "Bearer secret-token");
        assertThat(scene).hasValue(CredentialSceneType.MCP_AUTH_TOKEN);
    }

    @Test
    void spiOverridesBuiltinHeaderOnSameKey() {
        ExternalAuthenticator authenticator = (ignoredTarget, ignoredConfig) -> new AuthMaterial(
            Map.of("Authorization", "Bearer spi-token"), Map.of(), Map.of());
        CredentialDecryptor decryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                return "builtin-token";
            }
        };
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(authenticator, decryptor);

        AuthMaterial material = merger.merge(target,
            new ExternalAuthConfig("bearer", "Authorization", null, "ENC(token)", null, Map.of()));

        assertThat(material.headers()).containsEntry("Authorization", "Bearer spi-token");
    }

    @Test
    void rejectsConfiguredAuthTypeWhenMergedMaterialEmpty() {
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(),
            new CredentialDecryptor() {
                @Override
                public String decrypt(String ciphertext) {
                    return ciphertext;
                }

                @Override
                public String decrypt(String ciphertext, int sceneType) {
                    return ciphertext;
                }
            });

        assertThatThrownBy(() -> merger.merge(target,
            new ExternalAuthConfig("bearer", "Authorization", null, null, null, Map.of())))
            .isInstanceOf(ExternalAuthenticationException.class);
    }

    @Test
    void materialExtensionsAreNotMergedIntoHttpMaps() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("expiresAt", 12345L);
        ExternalAuthenticator authenticator = (ignoredTarget, ignoredConfig) -> new AuthMaterial(Map.of("X-Api-Key",
            "value"), Map.of(), extensions);
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(authenticator,
            new CredentialDecryptor() {
                @Override
                public String decrypt(String ciphertext) {
                    return ciphertext;
                }

                @Override
                public String decrypt(String ciphertext, int sceneType) {
                    return ciphertext;
                }
            });

        AuthMaterial material = merger.merge(target, ExternalAuthConfig.none());

        assertThat(material.headers()).containsEntry("X-Api-Key", "value");
        assertThat(material.materialExtensions()).containsEntry("expiresAt", 12345L);
        assertThat(material.queryParams()).isEmpty();
    }
}
