/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.security;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

/**
 * Example {@link FineGrainedAuthorizer} for the security demo module.
 *
 * <p>Requires {@code X-User-ID} on ingress requests. Replace with institutional IAM in production.
 *
 * @since 0.1.0
 */
public final class DemoFineGrainedAuthorizer implements FineGrainedAuthorizer {
    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            return AuthorizationResult.deny("X-User-ID header is required");
        }
        return AuthorizationResult.allow();
    }
}
