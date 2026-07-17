/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.app.config.SecurityProperties;
import com.openjiuwen.service.app.security.AuthorizationDeniedExceptionHandler;
import com.openjiuwen.service.app.security.FineGrainedAuthorizerBootstrapValidator;
import com.openjiuwen.service.app.security.ResourceAuthorizationAspect;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Auto-configuration tests for ingress security.
 */
class SecurityAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, SecurityAutoConfiguration.class));

    @Test
    void securityDisabledDoesNotRegisterAuthBeans() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ResourceAuthorizationAspect.class);
            assertThat(context).doesNotHaveBean(FineGrainedAuthorizerBootstrapValidator.class);
        });
    }

    @Test
    void authEnabledWithoutAuthorizerFailsStartup() {
        contextRunner.withPropertyValues("openjiuwen.service.security.enabled=true",
            "openjiuwen.service.security.auth.enabled=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("FineGrainedAuthorizer");
            });
    }

    @Test
    void authEnabledWithAuthorizerRegistersAspect() {
        contextRunner.withPropertyValues("openjiuwen.service.security.enabled=true",
            "openjiuwen.service.security.auth.enabled=true")
            .withBean(FineGrainedAuthorizer.class, () -> request -> AuthorizationResult.allow())
            .run(context -> {
                assertThat(context).hasSingleBean(ResourceAuthorizationAspect.class);
                assertThat(context).hasSingleBean(AuthorizationDeniedExceptionHandler.class);
            });
    }

    @Test
    void bindsSecurityProperties() {
        contextRunner.withPropertyValues("openjiuwen.service.security.enabled=true",
            "openjiuwen.service.security.tls.client-auth=need",
            "openjiuwen.service.security.auth.enabled=false")
            .run(context -> {
                SecurityProperties properties = context.getBean(SecurityProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getTls().getClientAuth()).isEqualTo("need");
                assertThat(properties.getAuth().isEnabled()).isFalse();
            });
    }
}
