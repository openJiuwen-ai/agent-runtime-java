/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.config.SecurityProperties;
import com.openjiuwen.service.spec.security.TlsMaterial;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Unit tests for {@link TlsStartupValidator}.
 */
class TlsStartupValidatorTest {
    @Test
    void missingKeyStoreFailsValidation() {
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        TlsMaterial material = new TlsMaterial(null, new char[0], "PKCS12", null, new char[0], "PKCS12",
            java.util.List.of("TLSv1.3"), true);
        assertThatThrownBy(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key-store");
    }

    @Test
    void needClientAuthWithoutTrustStoreFailsValidation() {
        SecurityProperties.Tls tls = new SecurityProperties.Tls();
        tls.setClientAuth("need");
        tls.setKeyStore("classpath:security/test-keystore.p12");
        TlsMaterial material = new TlsMaterial("classpath:security/test-keystore.p12", "secret".toCharArray(), "PKCS12",
            null, new char[0], "PKCS12", java.util.List.of("TLSv1.3"), true);
        assertThatThrownBy(() -> TlsStartupValidator.validate(tls, material, new DefaultResourceLoader()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trust-store");
    }
}
