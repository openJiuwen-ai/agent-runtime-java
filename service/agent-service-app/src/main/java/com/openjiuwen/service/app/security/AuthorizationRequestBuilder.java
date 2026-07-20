/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.app.controller.query.QueryIngressSupport;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

import java.util.Map;

/**
 * Builds {@link AuthorizationRequest} instances for the authorization aspect.
 *
 * @since 0.1.0
 */
public final class AuthorizationRequestBuilder {
    private static final Map<String, Object> EMPTY_EXTENSIONS = Map.of();

    private AuthorizationRequestBuilder() {
    }

    /**
     * Builds an authorization request from the current servlet request and resource annotation.
     *
     * @param authorizedResource resource annotation
     * @param request current HTTP servlet request
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpServletRequest request) {
        return build(authorizedResource, toHeaders(request));
    }

    /**
     * Builds an authorization request from HTTP headers and resource annotation.
     *
     * @param authorizedResource resource annotation
     * @param headers HTTP headers
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpHeaders headers) {
        return new AuthorizationRequest(authorizedResource.resource(), authorizedResource.action(),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_USER_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_SPACE_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_TENANT_ID)), EMPTY_EXTENSIONS);
    }

    private static HttpHeaders toHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            request.getHeaders(name).asIterator().forEachRemaining(value -> headers.add(name, value));
        });
        return headers;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
