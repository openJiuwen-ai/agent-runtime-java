/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

/**
 * Fine-grained authorization extension point. Implementations are provided by the
 * application as a Spring {@code @Bean} and may call institutional IAM / RBAC / ABAC.
 * The runtime does not embed IAM client logic.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface FineGrainedAuthorizer {
    /**
     * Authorizes an ingress REST invocation.
     *
     * @param request authorization request assembled by the runtime
     * @return authorization result
     */
    AuthorizationResult authorize(AuthorizationRequest request);
}
