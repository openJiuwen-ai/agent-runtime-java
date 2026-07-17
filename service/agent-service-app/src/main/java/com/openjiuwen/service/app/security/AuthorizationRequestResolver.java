/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ServerWebExchange;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

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
        ServerWebExchange exchange = findArgument(joinPoint, ServerWebExchange.class);
        if (exchange != null) {
            return AuthorizationRequestBuilder.build(authorizedResource, exchange);
        }
        HttpHeaders headers = findArgument(joinPoint, HttpHeaders.class);
        if (headers != null) {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            return AuthorizationRequestBuilder.build(authorizedResource, headers, httpMethod(method),
                requestPath(method), clientIp(headers));
        }
        throw new IllegalStateException("No ingress request context available for authorization");
    }

    private static String httpMethod(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) {
            return "POST";
        }
        if (method.isAnnotationPresent(GetMapping.class)) {
            return "GET";
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return "PUT";
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return "DELETE";
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return "PATCH";
        }
        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
        if (requestMapping != null && requestMapping.method().length == 1) {
            return requestMapping.method()[0].name();
        }
        return "UNKNOWN";
    }

    private static String requestPath(Method method) {
        String path = firstPath(method.getAnnotation(PostMapping.class));
        if (path != null) {
            return path;
        }
        path = firstPath(method.getAnnotation(GetMapping.class));
        if (path != null) {
            return path;
        }
        path = firstPath(method.getAnnotation(PutMapping.class));
        if (path != null) {
            return path;
        }
        path = firstPath(method.getAnnotation(DeleteMapping.class));
        if (path != null) {
            return path;
        }
        path = firstPath(method.getAnnotation(PatchMapping.class));
        if (path != null) {
            return path;
        }
        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
        if (requestMapping != null) {
            if (requestMapping.path().length > 0) {
                return requestMapping.path()[0];
            }
            if (requestMapping.value().length > 0) {
                return requestMapping.value()[0];
            }
        }
        return "";
    }

    private static String firstPath(Annotation mapping) {
        if (mapping == null) {
            return null;
        }
        if (mapping instanceof PostMapping postMapping) {
            return firstNonBlank(postMapping.path(), postMapping.value());
        }
        if (mapping instanceof GetMapping getMapping) {
            return firstNonBlank(getMapping.path(), getMapping.value());
        }
        if (mapping instanceof PutMapping putMapping) {
            return firstNonBlank(putMapping.path(), putMapping.value());
        }
        if (mapping instanceof DeleteMapping deleteMapping) {
            return firstNonBlank(deleteMapping.path(), deleteMapping.value());
        }
        if (mapping instanceof PatchMapping patchMapping) {
            return firstNonBlank(patchMapping.path(), patchMapping.value());
        }
        return null;
    }

    private static String firstNonBlank(String[] primary, String[] secondary) {
        if (primary != null && primary.length > 0 && !primary[0].isBlank()) {
            return primary[0];
        }
        if (secondary != null && secondary.length > 0 && !secondary[0].isBlank()) {
            return secondary[0];
        }
        return "";
    }

    private static String clientIp(HttpHeaders headers) {
        String forwarded = headers.getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return null;
    }

    private static <T> T findArgument(ProceedingJoinPoint joinPoint, Class<T> type) {
        for (Object argument : joinPoint.getArgs()) {
            if (type.isInstance(argument)) {
                return type.cast(argument);
            }
        }
        return null;
    }
}
