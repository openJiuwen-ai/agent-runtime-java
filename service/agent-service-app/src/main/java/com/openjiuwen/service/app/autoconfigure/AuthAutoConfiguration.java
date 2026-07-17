/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.app.security.AuthorizationDeniedExceptionHandler;
import com.openjiuwen.service.app.security.FineGrainedAuthorizerBootstrapValidator;
import com.openjiuwen.service.app.security.ResourceAuthorizationAspect;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Fine-grained authorization auto-configuration (AOP + 403 handler).
 *
 * @since 0.1.0
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnProperty(prefix = "openjiuwen.service.security", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "openjiuwen.service.security.auth", name = "enabled", havingValue = "true")
public class AuthAutoConfiguration {
    /**
     * Registers the authorization aspect.
     *
     * @param authorizer fine-grained authorizer bean
     * @return authorization aspect
     */
    @Bean
    public ResourceAuthorizationAspect resourceAuthorizationAspect(FineGrainedAuthorizer authorizer) {
        return new ResourceAuthorizationAspect(authorizer);
    }

    /**
     * Registers the 403 exception handler.
     *
     * @return exception handler
     */
    @Bean
    public AuthorizationDeniedExceptionHandler authorizationDeniedExceptionHandler() {
        return new AuthorizationDeniedExceptionHandler();
    }

    /**
     * Validates that a {@link FineGrainedAuthorizer} bean exists.
     *
     * @param authorizerProvider authorizer provider
     * @return bootstrap validator
     */
    @Bean
    public FineGrainedAuthorizerBootstrapValidator fineGrainedAuthorizerBootstrapValidator(
        ObjectProvider<FineGrainedAuthorizer> authorizerProvider) {
        return new FineGrainedAuthorizerBootstrapValidator(authorizerProvider);
    }
}
