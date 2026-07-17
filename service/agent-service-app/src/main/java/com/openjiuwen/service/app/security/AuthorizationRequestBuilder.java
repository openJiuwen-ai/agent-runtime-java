/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.app.controller.query.QueryIngressSupport;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@link AuthorizationRequest} instances for the authorization aspect.
 *
 * @since 0.1.0
 */
public final class AuthorizationRequestBuilder {
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
        return build(authorizedResource, toHeaders(request), request.getMethod(), request.getRequestURI(),
            request.getRemoteAddr());
    }

    /**
     * Builds an authorization request from a WebFlux {@link ServerWebExchange}.
     *
     * @param authorizedResource resource annotation
     * @param exchange current server web exchange
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String clientIp = resolveClientIp(headers, exchange.getRequest().getRemoteAddress());
        return build(authorizedResource, headers, exchange.getRequest().getMethod().name(),
            exchange.getRequest().getPath().value(), clientIp);
    }

    /**
     * Builds an authorization request from WebFlux headers and mapping metadata.
     *
     * @param authorizedResource resource annotation
     * @param headers HTTP headers
     * @param httpMethod HTTP method
     * @param requestPath request path
     * @param clientIp client IP, may be {@code null}
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpHeaders headers,
        String httpMethod, String requestPath, String clientIp) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("httpMethod", httpMethod);
        extensions.put("requestPath", requestPath);
        if (clientIp != null && !clientIp.isBlank()) {
            extensions.put("clientIp", clientIp);
        }
        return new AuthorizationRequest(authorizedResource.resource(), authorizedResource.action(),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_USER_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_SPACE_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_TENANT_ID)), Map.copyOf(extensions));
    }

    private static HttpHeaders toHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            request.getHeaders(name).asIterator().forEachRemaining(value -> headers.add(name, value));
        });
        return headers;
    }

    private static String resolveClientIp(HttpHeaders headers, InetSocketAddress remoteAddress) {
        String forwarded = headers.getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
