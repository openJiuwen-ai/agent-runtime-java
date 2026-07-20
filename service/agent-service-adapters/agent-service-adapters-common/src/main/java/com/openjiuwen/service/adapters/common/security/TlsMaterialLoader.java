/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.spec.security.TlsMaterial;

import java.util.List;

/**
 * Loads {@link TlsMaterial} from configuration strings and optional encrypted passwords.
 *
 * @since 0.1.0
 */
public final class TlsMaterialLoader {
    private TlsMaterialLoader() {
    }

    /**
     * Builds TLS material from raw configuration values.
     *
     * @param keyStore key store location
     * @param keyStorePassword key store password (may be ciphertext)
     * @param keyStoreType key store type
     * @param trustStore trust store location
     * @param trustStorePassword trust store password (may be ciphertext)
     * @param trustStoreType trust store type
     * @param enabledProtocols enabled TLS protocols
     * @param verifyHostname whether outbound clients should verify hostnames
     * @param credentialDecryptor credential decryptor
     * @return loaded TLS material
     */
    public static TlsMaterial load(String keyStore, String keyStorePassword, String keyStoreType, String trustStore,
        String trustStorePassword, String trustStoreType, List<String> enabledProtocols, boolean verifyHostname,
        CredentialDecryptor credentialDecryptor) {
        char[] keyPassword = decryptPassword(keyStorePassword, CredentialSceneType.TLS_KEYSTORE_PASSWORD,
            credentialDecryptor);
        char[] trustPassword = decryptPassword(trustStorePassword, CredentialSceneType.TLS_TRUSTSTORE_PASSWORD,
            credentialDecryptor);
        return new TlsMaterial(keyStore, keyPassword, defaultStoreType(keyStoreType), trustStore, trustPassword,
            defaultStoreType(trustStoreType), enabledProtocols == null ? List.of() : List.copyOf(enabledProtocols),
            verifyHostname);
    }

    private static char[] decryptPassword(String ciphertext, int sceneType, CredentialDecryptor credentialDecryptor) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return new char[0];
        }
        String plain = credentialDecryptor.decrypt(ciphertext, sceneType);
        return plain == null ? new char[0] : plain.toCharArray();
    }

    private static String defaultStoreType(String storeType) {
        return storeType == null || storeType.isBlank() ? "PKCS12" : storeType;
    }
}
