/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.security.TlsMaterialLoader;
import com.openjiuwen.service.app.config.SecurityProperties;
import com.openjiuwen.service.app.security.TlsStartupValidator;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * Maps {@code openjiuwen.service.security.tls.*} to embedded servlet container SSL settings.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.security", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "openjiuwen.service.security.tls", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ConfigurableServletWebServerFactory.class)
public class TlsAutoConfiguration {
    /**
     * Customizes the servlet web server factory with ingress TLS / mTLS settings.
     *
     * @param securityProperties security properties
     * @param credentialDecryptor credential decryptor
     * @param resourceLoader resource loader
     * @return web server customizer
     */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> tlsWebServerCustomizer(
        SecurityProperties securityProperties, CredentialDecryptor credentialDecryptor, ResourceLoader resourceLoader) {
        SecurityProperties.Tls tls = securityProperties.getTls();
        TlsMaterial material = TlsMaterialLoader.load(tls.getKeyStore(), tls.getKeyStorePassword(), tls.getKeyStoreType(),
            tls.getTrustStore(), tls.getTrustStorePassword(), tls.getTrustStoreType(), tls.getEnabledProtocols(), true,
            credentialDecryptor);
        TlsStartupValidator.validate(tls, material, resourceLoader);
        Ssl ssl = toServerSsl(material, tls.getClientAuth());
        return factory -> factory.setSsl(ssl);
    }

    private static Ssl toServerSsl(TlsMaterial material, String clientAuth) {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setKeyStore(material.keyStoreLocation());
        ssl.setKeyStorePassword(new String(material.keyStorePassword()));
        ssl.setKeyStoreType(material.keyStoreType());
        if (material.trustStoreLocation() != null && !material.trustStoreLocation().isBlank()) {
            ssl.setTrustStore(material.trustStoreLocation());
            ssl.setTrustStorePassword(new String(material.trustStorePassword()));
            ssl.setTrustStoreType(material.trustStoreType());
        }
        if (material.enabledProtocols() != null && !material.enabledProtocols().isEmpty()) {
            ssl.setEnabledProtocols(material.enabledProtocols().toArray(String[]::new));
        }
        ssl.setClientAuth(mapClientAuth(clientAuth));
        return ssl;
    }

    private static Ssl.ClientAuth mapClientAuth(String clientAuth) {
        if (clientAuth == null) {
            return Ssl.ClientAuth.NONE;
        }
        return switch (clientAuth.trim().toLowerCase()) {
            case "want" -> Ssl.ClientAuth.WANT;
            case "need" -> Ssl.ClientAuth.NEED;
            default -> Ssl.ClientAuth.NONE;
        };
    }
}
