/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.AuthorizedResource;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * AOP entry point for {@link AuthorizedResource} on ingress REST controllers (Servlet + WebFlux).
 *
 * @since 0.1.0
 */
@Aspect
public class ResourceAuthorizationAspect {
    private final FineGrainedAuthorizer authorizer;

    /**
     * Creates the authorization aspect.
     *
     * @param authorizer fine-grained authorizer bean
     */
    public ResourceAuthorizationAspect(FineGrainedAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * Authorizes annotated controller methods before invocation.
     *
     * @param joinPoint join point
     * @param authorizedResource resource annotation
     * @return controller method result
     * @throws Throwable propagated from the target method
     */
    @Around("@annotation(authorizedResource)")
    public Object authorize(ProceedingJoinPoint joinPoint, AuthorizedResource authorizedResource) throws Throwable {
        AuthorizationRequest request = AuthorizationRequestResolver.resolve(joinPoint, authorizedResource);
        AuthorizationResult result = authorizer.authorize(request);
        if (!result.allowed()) {
            throw new AuthorizationDeniedException(result.reason(), authorizedResource.resource(),
                authorizedResource.action());
        }
        return joinPoint.proceed();
    }
}
