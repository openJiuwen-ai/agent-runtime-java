/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Resolves {@link AuthorizationRequest} for servlet and WebFlux ingress controllers.
 *
 * @since 0.1.0
 */
public final class AuthorizationRequestResolver {
    private AuthorizationRequestResolver() {
    }

    /**
     * Resolves an authorization request from the current join point.
     *
     * @param joinPoint AOP join point
     * @param authorizedResource resource annotation
     * @return authorization request
     */
    public static AuthorizationRequest resolve(ProceedingJoinPoint joinPoint, AuthorizedResource authorizedResource) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return AuthorizationRequestBuilder.build(authorizedResource, servletAttributes.getRequest());
        }
        return findArgument(joinPoint, HttpHeaders.class)
            .map(headers -> AuthorizationRequestBuilder.build(authorizedResource, headers))
            .orElseThrow(() -> new IllegalStateException("No ingress request context available for authorization"));
    }

    private static <T> Optional<T> findArgument(ProceedingJoinPoint joinPoint, Class<T> type) {
        for (Object argument : joinPoint.getArgs()) {
            if (type.isInstance(argument)) {
                return Optional.of(type.cast(argument));
            }
        }
        return Optional.empty();
    }
}
