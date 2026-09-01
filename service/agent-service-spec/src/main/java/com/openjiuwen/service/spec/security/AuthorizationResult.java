/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

/**
 * Fine-grained authorization outcome returned by {@link FineGrainedAuthorizer}.
 *
 * @param allowed whether the request is permitted
 * @param reason optional deny reason written into the HTTP 403 body
 * @since 0.1.0
 */
public record AuthorizationResult(boolean allowed, String reason) {
    /**
     * Creates an allow result.
     *
     * @return allow result
     */
    public static AuthorizationResult allow() {
        return new AuthorizationResult(true, null);
    }

    /**
     * Creates a deny result.
     *
     * @param reason optional deny reason
     * @return deny result
     */
    public static AuthorizationResult deny(String reason) {
        return new AuthorizationResult(false, reason);
    }
}
