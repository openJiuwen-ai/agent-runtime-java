/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.app.config.SecurityProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Aggregator auto-configuration for ingress TLS and fine-grained authorization.
 *
 * <p>This class intentionally declares no beans directly. It acts as the single
 * {@code AutoConfiguration.imports} entry: enables {@link SecurityProperties}, gates the whole
 * security feature on {@code openjiuwen.service.security.enabled}, orders after
 * {@link CredentialDecryptorAutoConfiguration}, and {@linkplain Import imports}
 * {@link TlsAutoConfiguration} and {@link AuthAutoConfiguration}.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = CredentialDecryptorAutoConfiguration.class)
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.security", name = "enabled", havingValue = "true")
@Import({TlsAutoConfiguration.class, AuthAutoConfiguration.class})
public class SecurityAutoConfiguration {
}
