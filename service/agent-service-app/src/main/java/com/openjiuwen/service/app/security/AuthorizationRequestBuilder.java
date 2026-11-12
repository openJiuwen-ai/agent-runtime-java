/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import com.openjiuwen.service.app.controller.query.QueryIngressSupport;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriTemplate;

import java.util.Map;

/**
 * Builds {@link AuthorizationRequest} instances for the authorization aspect.
 *
 * <p>When the matched endpoint declares a {@code sessionId} path variable the
 * builder injects it under {@link AgentServicePaths#EXTENSION_RESOURCE_ID} so
 * authorizers can enforce resource-level ("may this subject touch this
 * session") checks on top of subject-level decisions.</p>
 *
 * @since 0.1.0
 */
public final class AuthorizationRequestBuilder {
    private static final Map<String, Object> EMPTY_EXTENSIONS = Map.of();

    private static final String SESSION_ID_VARIABLE = "sessionId";

    private AuthorizationRequestBuilder() {
    }

    /**
     * Builds an authorization request from the current servlet request and resource annotation.
     *
     * @param authorizedResource resource annotation
     * @param request current HTTP servlet request
     * @param lookupPath request path without context or servlet prefix, may be null
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpServletRequest request,
            String lookupPath) {
        return build(authorizedResource, toHeaders(request), lookupPath);
    }

    /**
     * Builds an authorization request from HTTP headers and resource annotation.
     *
     * @param authorizedResource resource annotation
     * @param headers HTTP headers
     * @param lookupPath request path without context or servlet prefix, may be null
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpHeaders headers,
            String lookupPath) {
        return new AuthorizationRequest(authorizedResource.resource(), authorizedResource.action(),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_USER_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_SPACE_ID)),
            blankToNull(headers.getFirst(QueryIngressSupport.HEADER_TENANT_ID)), extensions(lookupPath));
    }

    /**
     * Builds an authorization request from the current servlet request and resource
     * annotation, without path-variable extension extraction.
     *
     * @param authorizedResource resource annotation
     * @param request current HTTP servlet request
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpServletRequest request) {
        return build(authorizedResource, toHeaders(request), null);
    }

    /**
     * Builds an authorization request from HTTP headers and resource annotation,
     * without path-variable extension extraction.
     *
     * @param authorizedResource resource annotation
     * @param headers HTTP headers
     * @return authorization request
     */
    public static AuthorizationRequest build(AuthorizedResource authorizedResource, HttpHeaders headers) {
        return build(authorizedResource, headers, null);
    }

    /**
     * Extracts the {@code sessionId} path variable from the lookup path and
     * exposes it under the {@code resource.id} extension key; endpoints without
     * a session-scoped path keep the empty extensions map.
     *
     * @param lookupPath request path without context or servlet prefix, may be null
     * @return extensions map carrying the target session id when resolvable
     */
    static Map<String, Object> extensions(String lookupPath) {
        if (lookupPath == null || lookupPath.isBlank()) {
            return EMPTY_EXTENSIONS;
        }
        String sessionId = new UriTemplate(AgentServicePaths.SESSION_DETAIL).match(lookupPath)
                .get(SESSION_ID_VARIABLE);
        if (sessionId == null || sessionId.isBlank()) {
            return EMPTY_EXTENSIONS;
        }
        return Map.of(AgentServicePaths.EXTENSION_RESOURCE_ID, sessionId);
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
