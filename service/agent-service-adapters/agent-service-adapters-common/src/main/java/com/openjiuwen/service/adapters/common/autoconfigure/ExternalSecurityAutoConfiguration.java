/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.autoconfigure;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.security.ExternalAuthMaterialMerger;
import com.openjiuwen.service.adapters.common.security.ExternalHttpClientFactory;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.adapters.common.security.ExternalTlsConfigResolver;
import com.openjiuwen.service.adapters.common.security.GlobalTlsProperties;
import com.openjiuwen.service.adapters.common.security.NoOpExternalAuthenticator;
import com.openjiuwen.service.spec.security.ExternalAuthenticator;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

/**
 * Auto-configuration for outbound external-service security.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@Import(CredentialDecryptorAutoConfiguration.class)
@EnableConfigurationProperties(GlobalTlsProperties.class)
public class ExternalSecurityAutoConfiguration {
    /**
     * Default no-op outbound authenticator.
     *
     * @return no-op authenticator bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalAuthenticator.class)
    public ExternalAuthenticator externalAuthenticator() {
        return new NoOpExternalAuthenticator();
    }

    /**
     * Resolves TLS material for outbound endpoints.
     *
     * @param globalTlsProperties global TLS properties
     * @param credentialDecryptor credential decryptor
     * @return TLS config resolver bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalTlsConfigResolver.class)
    public ExternalTlsConfigResolver externalTlsConfigResolver(GlobalTlsProperties globalTlsProperties,
        CredentialDecryptor credentialDecryptor) {
        return new ExternalTlsConfigResolver(globalTlsProperties, credentialDecryptor);
    }

    /**
     * Merges built-in and SPI auth overlays.
     *
     * @param authenticator outbound authenticator
     * @param credentialDecryptor credential decryptor
     * @return auth material merger bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalAuthMaterialMerger.class)
    public ExternalAuthMaterialMerger externalAuthMaterialMerger(ExternalAuthenticator authenticator,
        CredentialDecryptor credentialDecryptor) {
        return new ExternalAuthMaterialMerger(authenticator, credentialDecryptor);
    }

    /**
     * Builds outbound HTTP clients.
     *
     * @param resourceLoader resource loader
     * @return HTTP client factory bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalHttpClientFactory.class)
    public ExternalHttpClientFactory externalHttpClientFactory(ResourceLoader resourceLoader) {
        return new ExternalHttpClientFactory(resourceLoader);
    }

    /**
     * Orchestrates outbound TLS/auth preparation for adapters.
     *
     * @param tlsConfigResolver TLS config resolver
     * @param authMaterialMerger auth material merger
     * @param httpClientFactory HTTP client factory
     * @return outbound security support bean
     */
    @Bean
    @ConditionalOnMissingBean(ExternalOutboundSecuritySupport.class)
    public ExternalOutboundSecuritySupport externalOutboundSecuritySupport(ExternalTlsConfigResolver tlsConfigResolver,
        ExternalAuthMaterialMerger authMaterialMerger, ExternalHttpClientFactory httpClientFactory) {
        return new ExternalOutboundSecuritySupport(tlsConfigResolver, authMaterialMerger, httpClientFactory);
    }
}
