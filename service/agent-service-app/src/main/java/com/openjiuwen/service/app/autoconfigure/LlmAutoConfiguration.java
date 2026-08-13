/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for reusable LLM configuration resolution.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = CredentialDecryptorAutoConfiguration.class)
@Import(CredentialDecryptorAutoConfiguration.class)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmAutoConfiguration {
    /**
     * Creates the resolver that applies optional file values and credential
     * decryption.
     *
     * @param properties raw LLM properties
     * @param environment Spring environment
     * @param credentialDecryptor credential decryption SPI
     * @return LLM configuration resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmConfigResolver llmConfigResolver(LlmProperties properties, Environment environment,
        CredentialDecryptor credentialDecryptor) {
        return new LlmConfigResolver(properties, environment, credentialDecryptor);
    }
}
