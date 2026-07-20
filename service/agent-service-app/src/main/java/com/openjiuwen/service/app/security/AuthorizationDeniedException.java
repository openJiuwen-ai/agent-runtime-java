/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

/**
 * Thrown when {@link com.openjiuwen.service.spec.security.FineGrainedAuthorizer} denies access.
 *
 * @since 0.1.0
 */
public class AuthorizationDeniedException extends RuntimeException {
    private final String resource;

    private final String action;

    /**
     * Creates a denial exception.
     *
     * @param reason optional deny reason
     * @param resource resource identifier
     * @param action action identifier
     */
    public AuthorizationDeniedException(String reason, String resource, String action) {
        super(reason == null ? "access denied" : reason);
        this.resource = resource;
        this.action = action;
    }

    /**
     * Returns the resource identifier.
     *
     * @return resource
     */
    public String getResource() {
        return resource;
    }

    /**
     * Returns the action identifier.
     *
     * @return action
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the deny reason for the HTTP body, if any.
     *
     * @return reason or {@code null}
     */
    public String getReason() {
        return getMessage();
    }
}
