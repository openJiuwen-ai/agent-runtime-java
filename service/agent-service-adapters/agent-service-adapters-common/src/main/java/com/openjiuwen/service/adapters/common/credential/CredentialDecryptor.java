/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.credential;

/**
 * Decrypts sensitive values from configuration (e.g.
 * {@code encrypted-password}) before use in clients.
 * Middleware (#9) and External (#11) adapters share the same bean.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface CredentialDecryptor {
    /**
     * decrypt
     *
     * @param ciphertext value from configuration; {@code null} or blank means no
     *            credential
     * @return plaintext for connection or authentication
     */
    String decrypt(String ciphertext);

    /**
     * Decrypts a sensitive value for a specific credential scene.
     * <p>
     * The default implementation preserves compatibility with existing
     * single-argument implementations.
     * </p>
     *
     * @param ciphertext value from configuration
     * @param sceneType credential scene defined by {@link CredentialSceneType}
     * @return plaintext for connection or authentication
     */
    default String decrypt(String ciphertext, int sceneType) {
        return decrypt(ciphertext);
    }
}
