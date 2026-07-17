/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.config.SecurityProperties;
import com.openjiuwen.service.app.security.tls.TlsTestCertificates;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link TlsStartupValidator}.
 *
 * @since 0.1.0
 */
class TlsStartupValidatorTest {
    @Test
    void missingKeyStoreFailsValidation() {
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        TlsMaterial material = new TlsMaterial(null, new char[0], "PKCS12", null, new char[0], "PKCS12",
            List.of("TLSv1.3"), true);
        assertThatThrownBy(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-store");
    }

    @Test
    void needClientAuthWithoutTrustStoreFailsValidation() {
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        tls.setClientAuth("need");
        tls.setKeyStore("classpath:security/test-keystore.p12");
        TlsMaterial material = new TlsMaterial("classpath:security/test-keystore.p12", "secret".toCharArray(), "PKCS12",
            null, new char[0], "PKCS12", List.of("TLSv1.3"), true);
        assertThatThrownBy(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust-store");
    }

    @Test
    void expiredCertificateWithWarnPolicyDoesNotFailValidation() throws Exception {
        Path directory = Files.createTempDirectory("agent-service-tls-expired-warn-");
        Path expiredKeyStore = TlsTestCertificates.generateExpiredServerKeyStore(directory);
        String location = expiredKeyStore.toUri().toString();
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        tls.setKeyStore(location);
        tls.setCertificateExpiryPolicy("warn");
        TlsMaterial material = new TlsMaterial(location, TlsTestCertificates.PASSWORD.toCharArray(), "PKCS12", null,
            new char[0], "PKCS12", List.of("TLSv1.3"), true);

        assertThatCode(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .doesNotThrowAnyException();
    }

    @Test
    void expiredCertificateWithFailPolicyFailsValidation() throws Exception {
        Path directory = Files.createTempDirectory("agent-service-tls-expired-fail-");
        Path expiredKeyStore = TlsTestCertificates.generateExpiredServerKeyStore(directory);
        String location = expiredKeyStore.toUri().toString();
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        tls.setKeyStore(location);
        tls.setCertificateExpiryPolicy("fail");
        TlsMaterial material = new TlsMaterial(location, TlsTestCertificates.PASSWORD.toCharArray(), "PKCS12", null,
            new char[0], "PKCS12", List.of("TLSv1.3"), true);

        assertThatThrownBy(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("certificate expired");
    }
}
