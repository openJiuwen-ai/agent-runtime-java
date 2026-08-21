/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Registry for the outbound A2A propagation-header provider. Consumers (for example an
 * observability feature) register a provider that computes propagation headers per
 * request; {@link HeaderInjectingA2AHttpClient} invokes it at request-execution time.
 *
 * <p>The registry is deliberately function-shaped rather than a static key/value table:
 * values such as W3C {@code traceparent} are dynamic per request and must be computed
 * from the request (url/body) at send time.
 *
 * <p>When no provider is registered, {@link #provide(String, String)} yields an empty
 * map and no header is injected, so behavior is identical to the plain client (opt-in
 * only).
 *
 * <p>Provider contract:
 * <ul>
 *   <li>Only one provider is active per JVM; registering again overwrites the previous
 *   one. The returned {@link Registration} removes only its own provider on close.</li>
 *   <li>The provider is invoked on the HTTP client's worker threads, not on the caller
 *   thread — it must be thread-safe and must not rely on caller-thread
 *   {@code ThreadLocal} state; capturing propagation context across threads is the
 *   provider's responsibility.</li>
 *   <li>Provider headers are added after SDK-set headers, so a same-named provider
 *   header overwrites the SDK value. Providers should restrict themselves to
 *   propagation headers (for example {@code traceparent}, {@code tracestate},
 *   {@code baggage}).</li>
 *   <li>Exceptions propagate (fail-closed): a {@code RuntimeException} from the
 *   provider fails the request before any HTTP I/O. Pure-observability providers should
 *   handle their own errors and return an empty map instead of throwing.</li>
 * </ul>
 *
 * @since 0.1.2
 */
public final class A2APropagationHeaderRegistry {
    private static volatile BiFunction<String, String, Map<String, String>> provider;

    private A2APropagationHeaderRegistry() {
    }

    /**
     * Registers the propagation-header provider. Passing {@code null} disables
     * injection again.
     *
     * @param headerProvider function computing headers from (url, body); may be null
     * @return handle for this registration; closing it removes only this provider
     */
    public static Registration registerProvider(BiFunction<String, String, Map<String, String>> headerProvider) {
        provider = headerProvider;
        return new Registration(headerProvider);
    }

    /**
     * Computes the headers to inject for one request.
     *
     * @param url  target url
     * @param body request body (may be null for GET/DELETE)
     * @return headers to inject (empty when no provider is registered or it returns null)
     */
    public static Map<String, String> provide(String url, String body) {
        BiFunction<String, String, Map<String, String>> current = provider;
        if (current == null) {
            return Map.of();
        }
        Map<String, String> headers = current.apply(url, body);
        return headers != null ? headers : Map.of();
    }

    /**
     * Handle for one provider registration. {@link #close()} unregisters the provider
     * only if it is still the active one, so an outdated handle cannot remove a newer
     * registration.
     *
     * @since 0.1.2
     */
    public static final class Registration implements AutoCloseable {
        private final BiFunction<String, String, Map<String, String>> registered;

        private Registration(BiFunction<String, String, Map<String, String>> registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            synchronized (A2APropagationHeaderRegistry.class) {
                if (provider == registered) {
                    provider = null;
                }
            }
        }
    }
}
