/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Registry for the outbound A2A propagation-header resolver. Consumers (for example an
 * observability feature) register a resolver that computes propagation headers per
 * request; {@link HeaderInjectingA2AHttpClient} invokes it at request-execution time.
 *
 * <p>The registry is deliberately resolver-shaped rather than a static key/value table:
 * values such as W3C {@code traceparent} are dynamic per request and must be computed
 * from the request (url/body) at send time.
 *
 * <p>When no resolver is registered, resolution yields an empty map and no header is
 * injected, so behavior is identical to the plain client (opt-in only).
 *
 * @since 0.1.2
 */
public final class A2APropagationHeaderSupport {

    private static volatile BiFunction<String, String, Map<String, String>> resolver;

    private A2APropagationHeaderSupport() {
    }

    /**
     * Registers the propagation-header resolver. Passing {@code null} disables
     * injection again.
     *
     * @param headerResolver function computing headers from (url, body); may be null
     */
    public static void setResolver(BiFunction<String, String, Map<String, String>> headerResolver) {
        resolver = headerResolver;
    }

    /**
     * Resolves the headers to inject for one request.
     *
     * @param url  target url
     * @param body request body (may be null for GET/DELETE)
     * @return headers to inject (empty when no resolver is registered or it returns null)
     */
    public static Map<String, String> resolve(String url, String body) {
        BiFunction<String, String, Map<String, String>> current = resolver;
        if (current == null) {
            return Map.of();
        }
        Map<String, String> headers = current.apply(url, body);
        return headers != null ? headers : Map.of();
    }
}
