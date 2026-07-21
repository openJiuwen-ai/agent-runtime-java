/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

/**
 * Outbound external service authentication extension point. Applications provide a
 * {@code @Bean} implementation for institutional token, HMAC, OAuth, and similar flows.
 * The runtime does not embed IAM client logic.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ExternalAuthenticator {
    /**
     * Builds outbound authentication material for a target endpoint.
     *
     * @param target target description
     * @param authConfig YAML-bound auth configuration including {@link ExternalAuthConfig#extensions()}
     * @return authentication overlay; {@link AuthMaterial#none()} means no overlay
     * @throws ExternalAuthenticationException when credentials cannot be obtained
     */
    AuthMaterial authenticate(ExternalTargetRef target, ExternalAuthConfig authConfig);
}
