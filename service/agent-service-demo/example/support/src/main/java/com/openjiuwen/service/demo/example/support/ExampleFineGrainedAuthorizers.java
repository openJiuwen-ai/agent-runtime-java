/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

/**
 * Example {@link FineGrainedAuthorizer} implementations for demo / test profiles.
 *
 * @since 0.1.0
 */
public final class ExampleFineGrainedAuthorizers {
    private ExampleFineGrainedAuthorizers() {
    }

    /**
     * Permits all annotated ingress resources. Replace with institutional IAM in production.
     *
     * @return permissive authorizer
     */
    public static FineGrainedAuthorizer permitAll() {
        return request -> AuthorizationResult.allow();
    }
}
