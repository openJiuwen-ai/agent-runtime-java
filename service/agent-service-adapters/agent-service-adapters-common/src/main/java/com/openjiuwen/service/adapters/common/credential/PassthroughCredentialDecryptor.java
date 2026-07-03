/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default decryptor that returns the input unchanged. Suitable for dev/local
 * only.
 *
 * @since 0.1.0
 */
public class PassthroughCredentialDecryptor implements CredentialDecryptor {

    private static final Logger log = LoggerFactory.getLogger(PassthroughCredentialDecryptor.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext != null && !ciphertext.isBlank() && WARNED.compareAndSet(false, true)) {
            log.warn("Passthrough CredentialDecryptor is active: encrypted-password is not decrypted");
        }
        return ciphertext;
    }
}
