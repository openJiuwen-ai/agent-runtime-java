/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.credential;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers a default {@link CredentialDecryptor} when no custom bean is
 * provided.
 *
 * @since 0.1.0
 */
@AutoConfiguration
public class CredentialDecryptorAutoConfiguration {
    /**
     * Registers the default passthrough credential decryptor.
     *
     * @return the credential decryptor bean
     */
    @Bean
    @ConditionalOnMissingBean(CredentialDecryptor.class)
    public CredentialDecryptor credentialDecryptor() {
        return new PassthroughCredentialDecryptor();
    }
}
