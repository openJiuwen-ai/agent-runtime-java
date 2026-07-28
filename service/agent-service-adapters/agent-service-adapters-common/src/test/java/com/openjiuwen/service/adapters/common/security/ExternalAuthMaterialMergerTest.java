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
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void mergePlainBearerTokenWithoutDecryption() {
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(),
            passthroughDecryptor());

        AuthMaterial material = merger.merge(target,
            new ExternalAuthConfig("bearer", "Authorization", "plain-token", null, null, Map.of()));

        assertThat(material.headers()).containsEntry("Authorization", "Bearer plain-token");
    }

    @Test
    void mergeHeaderAuthTypeUsesCustomHeaderName() {
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(),
            passthroughDecryptor());

        AuthMaterial material = merger.merge(target,
            new ExternalAuthConfig("header", "X-Sandbox-Token", null, "ENC(token)", null, Map.of()));

        assertThat(material.headers()).containsEntry("X-Sandbox-Token", "plain-ENC(token)");
        assertThat(material.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void mergeUsesRemoteAndSandboxSceneTypes() {
        AtomicInteger remoteScene = new AtomicInteger();
        AtomicInteger sandboxScene = new AtomicInteger();
        CredentialDecryptor decryptor = sceneRecordingDecryptor(remoteScene, sandboxScene);
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(), decryptor);

        ExternalTargetRef remoteTarget = new ExternalTargetRef("REMOTE", "remote-1", "https://remote.internal/a2a",
            CredentialSceneType.REMOTE_AUTH_TOKEN);
        ExternalTargetRef sandboxTarget = new ExternalTargetRef("SANDBOX", "sandbox-1", "https://sandbox.internal",
            CredentialSceneType.SANDBOX_AUTH_TOKEN);

        merger.merge(remoteTarget,
            new ExternalAuthConfig("bearer", "Authorization", null, "ENC(remote)", null, Map.of()));
        merger.merge(sandboxTarget,
            new ExternalAuthConfig("bearer", "Authorization", null, "ENC(sandbox)", null, Map.of()));

        assertThat(remoteScene).hasValue(CredentialSceneType.REMOTE_AUTH_TOKEN);
        assertThat(sandboxScene).hasValue(CredentialSceneType.SANDBOX_AUTH_TOKEN);
    }

    @Test
    void mergePassesAuthExtensionsToSpi() {
        Map<String, Object> extensions = Map.of("signingAlgorithm", "HMAC-SHA256", "keyRef", "vault:mcp-key");
        AtomicReference<Map<String, Object>> capturedExtensions = new AtomicReference<>();
        ExternalAuthenticator authenticator = (ignoredTarget, authConfig) -> {
            capturedExtensions.set(authConfig.extensions());
            return AuthMaterial.none();
        };
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(authenticator, passthroughDecryptor());

        assertThatThrownBy(() -> merger.merge(target,
            new ExternalAuthConfig("custom", "Authorization", null, null, null, extensions)))
            .isInstanceOf(ExternalAuthenticationException.class);
        assertThat(capturedExtensions.get()).containsEntry("signingAlgorithm", "HMAC-SHA256")
            .containsEntry("keyRef", "vault:mcp-key");
    }

    @Test
    void mergePropagatesDecryptFailure() {
        CredentialDecryptor failingDecryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                throw new IllegalStateException("decrypt failed");
            }
        };
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(),
            failingDecryptor);

        assertThatThrownBy(() -> merger.merge(target,
            new ExternalAuthConfig("bearer", "Authorization", null, "ENC(token)", null, Map.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("decrypt failed");
    }

    @Test
    void spiFailureIsPropagatedBeforeOutboundCall() {
        ExternalAuthenticator authenticator = (ignoredTarget, ignoredConfig) -> {
            throw new ExternalAuthenticationException("credential invalid");
        };
        ExternalAuthMaterialMerger merger = new ExternalAuthMaterialMerger(authenticator, passthroughDecryptor());

        assertThatThrownBy(
            () -> merger.merge(target, new ExternalAuthConfig("custom", "Authorization", null, null, null, Map.of())))
            .isInstanceOf(ExternalAuthenticationException.class)
            .hasMessageContaining("credential invalid");
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

    private static CredentialDecryptor sceneRecordingDecryptor(AtomicInteger remoteScene, AtomicInteger sandboxScene) {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                if (sceneType == CredentialSceneType.REMOTE_AUTH_TOKEN) {
                    remoteScene.set(sceneType);
                }
                if (sceneType == CredentialSceneType.SANDBOX_AUTH_TOKEN) {
                    sandboxScene.set(sceneType);
                }
                return "token-" + sceneType;
            }
        };
    }
}
