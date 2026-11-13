/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
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
     * Resolves an authorization request from the current join point, extracting
     * the target session id from the request path when the matched endpoint is
     * session-scoped.
     *
     * <p>On the servlet stack the lookup path is the request URI stripped of
     * the servlet context path, matching the context-less session templates.
     * On the reactive stack the request path is rebuilt from the matched
     * handler method's mapping annotation plus its {@code @PathVariable}
     * values, so session-scoped reactive endpoints carry the same
     * {@code resource.id} extension as their servlet counterparts.</p>
     *
     * @param joinPoint AOP join point
     * @param authorizedResource resource annotation
     * @return authorization request
     */
    public static AuthorizationRequest resolve(ProceedingJoinPoint joinPoint, AuthorizedResource authorizedResource) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String lookupPath = servletContextLessPath(request);
            return AuthorizationRequestBuilder.build(authorizedResource, request, lookupPath);
        }
        return findJoinPointHeader(joinPoint)
            .map(headers -> AuthorizationRequestBuilder.build(authorizedResource, headers,
                    reactiveLookupPath(joinPoint)))
            .orElseThrow(() -> new IllegalStateException("No ingress request context available for authorization"));
    }

    /**
     * Locates the ingress {@link HttpHeaders} argument of a reactive
     * controller join point, matching the parameter type against the join
     * point arguments.
     *
     * @param joinPoint AOP join point around a controller method
     * @return the ingress headers argument, empty when the method takes none
     */
    private static Optional<HttpHeaders> findJoinPointHeader(ProceedingJoinPoint joinPoint) {
        for (Object argument : joinPoint.getArgs()) {
            if (argument instanceof HttpHeaders headers) {
                return Optional.of(headers);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the request path without the servlet context prefix, so it
     * matches the application-internal session path templates even under
     * {@code server.servlet.context-path} deployments.
     *
     * @param request current servlet request
     * @return the context-less request path
     */
    private static String servletContextLessPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return UriComponentsBuilder.fromPath(requestUri).build().getPath();
    }

    /**
     * Rebuilds the request path of a reactive endpoint from the matched
     * handler method's mapping pattern, substituting {@code @PathVariable}
     * argument values; null when no mapping annotation is present, keeping
     * the extensions extraction disabled for non-mapped join points.
     *
     * @param joinPoint AOP join point around a controller method
     * @return the reconstructed request path, or null when not resolvable
     */
    private static String reactiveLookupPath(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        if (target == null) {
            return null;
        }
        Method method = findJoinPointMethod(joinPoint);
        if (method == null) {
            return null;
        }
        String pattern = mappingPattern(method);
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        return substitutePathVariables(method, List.of(joinPoint.getArgs()), pattern);
    }

    /**
     * Reads the first path value of the method's GetMapping or PostMapping
     * annotation.
     *
     * @param method the controller method carrying the mapping
     * @return the mapped path pattern, or null when the method maps no path
     */
    private static String mappingPattern(Method method) {
        GetMapping getMapping = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
        if (getMapping != null && getMapping.value().length > 0) {
            return getMapping.value()[0];
        }
        PostMapping postMapping = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
        if (postMapping != null && postMapping.value().length > 0) {
            return postMapping.value()[0];
        }
        return null;
    }

    /**
     * Locates the controller method the join point targets by matching the
     * method name and argument count against the target class declaration.
     *
     * @param joinPoint AOP join point
     * @return the declared controller method, or null when not found
     */
    private static Method findJoinPointMethod(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        for (Method candidate : target.getClass().getDeclaredMethods()) {
            if (candidate.getName().equals(joinPoint.getSignature().getName())
                    && candidate.getParameterCount() == joinPoint.getArgs().length) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Substitutes the {@code {placeholder}} segments of a mapping pattern with
     * the {@code @PathVariable} argument values bound to the same names; an
     * argument without a matching {@code @PathVariable} binding leaves its
     * placeholder untouched, which keeps the path unmatched by session
     * templates and the extensions map empty.
     *
     * @param method the controller method carrying the parameter annotations
     * @param arguments the join point argument values
     * @param pattern the mapping path pattern
     * @return the concrete request path
     */
    private static String substitutePathVariables(Method method, List<Object> arguments, String pattern) {
        Parameter[] parameters = method.getParameters();
        String resolved = pattern;
        for (int i = 0; i < parameters.length; i++) {
            PathVariable variable = parameters[i].getAnnotation(PathVariable.class);
            if (variable == null || arguments.get(i) == null) {
                continue;
            }
            String name = !variable.value().isEmpty() ? variable.value() : parameters[i].getName();
            resolved = resolved.replace("{" + name + "}", String.valueOf(arguments.get(i)));
        }
        return resolved;
    }
}
