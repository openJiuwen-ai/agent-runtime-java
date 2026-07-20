/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Fail-fast validator ensuring a {@link FineGrainedAuthorizer} exists when auth is enabled.
 *
 * @since 0.1.0
 */
public class FineGrainedAuthorizerBootstrapValidator implements InitializingBean {
    private final ObjectProvider<FineGrainedAuthorizer> authorizerProvider;

    /**
     * Creates the validator.
     *
     * @param authorizerProvider authorizer provider
     */
    public FineGrainedAuthorizerBootstrapValidator(ObjectProvider<FineGrainedAuthorizer> authorizerProvider) {
        this.authorizerProvider = authorizerProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (authorizerProvider.getIfAvailable() == null) {
            throw new IllegalStateException(
                "openjiuwen.service.security.auth.enabled=true requires a FineGrainedAuthorizer @Bean");
        }
    }
}
