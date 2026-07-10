/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.credential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PassthroughCredentialDecryptorTest
 *
 * @since 2026-07-03
 */
class PassthroughCredentialDecryptorTest {
    @Test
    void returnsInputUnchanged() {
        PassthroughCredentialDecryptor decryptor = new PassthroughCredentialDecryptor();
        assertThat(decryptor.decrypt("secret")).isEqualTo("secret");
        assertThat(decryptor.decrypt("llm-secret", CredentialSceneType.LLM_API_KEY)).isEqualTo("llm-secret");
        assertThat(decryptor.decrypt("")).isEmpty();
        assertThat(decryptor.decrypt(null)).isNull();
    }

    @Test
    void sceneAwareMethodKeepsLegacyLambdaCompatible() {
        CredentialDecryptor decryptor = ciphertext -> "plain:" + ciphertext;

        assertThat(decryptor.decrypt("cipher", CredentialSceneType.LLM_API_KEY)).isEqualTo("plain:cipher");
    }
}
