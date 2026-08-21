/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for the outbound A2A propagation-header provider. Consumers (for example an
 * observability feature) register a provider that computes propagation headers per
 * request; {@link HeaderInjectingA2AHttpClient} invokes it at request-execution time.
 *
 * <p>The registry is deliberately provider-shaped rather than a static key/value table:
 * values such as W3C {@code traceparent} are dynamic per request and must be computed
 * from the request coordinates at send time.
 *
 * <p>When no provider is registered, {@link #provide(String, String)} yields an empty
 * map and no header is injected, so behavior is identical to the plain client (opt-in
 * only).
 *
 * <p>Provider contract:
 * <ul>
 *   <li>Only one provider is active per JVM; registering again overwrites the previous
 *   one. The returned {@link Registration} removes only its own provider on close
 *   (compare-and-set), so an outdated handle cannot clear a newer registration even
 *   when registration and close race.</li>
 *   <li>The provider receives the outbound A-&gt;B request coordinates as an
 *   {@link A2AOutboundRequest} — not the inbound user-to-runtime request.</li>
 *   <li>The provider is invoked on the runtime's remote-call I/O worker threads
 *   ({@code A2ARemoteAgentClient}'s executor), not on the inbound request thread — it
 *   must be thread-safe and must not rely on caller-thread state such as
 *   {@code ThreadLocal}, logging MDC, OpenTelemetry {@code Context.current()} or the
 *   Spring request context; capturing and correlating propagation state across threads
 *   (for example via an explicit context stash) is the provider's responsibility.</li>
 *   <li>Provider headers are added after SDK-set headers via
 *   {@code addHeader}; the outcome for a same-named header is defined by the underlying
 *   HTTP client implementation (the JDK client keeps the last written value; custom
 *   clients may append or reject duplicates). Providers should restrict themselves to
 *   propagation-style headers (for example {@code traceparent}, {@code tracestate},
 *   {@code baggage}, {@code tenant-id}, {@code request-id}) and must not override
 *   protocol or credential headers such as {@code Content-Type}, {@code A2A-Version},
 *   {@code Host}, {@code Content-Length}, {@code Cookie} or {@code Authorization}.</li>
 *   <li>Exceptions propagate (fail-closed): a {@code RuntimeException} from the
 *   provider fails the request before any HTTP I/O. Pure-observability providers should
 *   handle their own errors and return an empty map instead of throwing.</li>
 * </ul>
 *
 * @since 0.1.2
 */
public final class A2APropagationHeaderRegistry {
    private static final AtomicReference<A2APropagationHeaderProvider> PROVIDER = new AtomicReference<>();

    private A2APropagationHeaderRegistry() {
    }

    /**
     * Registers the propagation-header provider. Passing {@code null} disables
     * injection again.
     *
     * @param headerProvider provider computing headers per request; may be null
     * @return handle for this registration; closing it removes only this provider
     */
    public static Registration registerProvider(A2APropagationHeaderProvider headerProvider) {
        PROVIDER.set(headerProvider);
        return new Registration(headerProvider);
    }

    /**
     * Computes the headers to inject for one request.
     *
     * @param request the outbound request coordinates
     * @return headers to inject (empty when no provider is registered or it returns null)
     */
    public static Map<String, String> provide(A2AOutboundRequest request) {
        A2APropagationHeaderProvider current = PROVIDER.get();
        if (current == null) {
            return Map.of();
        }
        Map<String, String> headers = current.headersFor(request);
        return headers != null ? headers : Map.of();
    }

    /**
     * Handle for one provider registration. {@link #close()} unregisters the provider
     * only if it is still the active one (compare-and-set), so an outdated handle
     * cannot remove a newer registration even when registration and close race.
     *
     * @since 0.1.2
     */
    public static final class Registration implements AutoCloseable {
        private final A2APropagationHeaderProvider registered;

        private Registration(A2APropagationHeaderProvider registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            PROVIDER.compareAndSet(registered, null);
        }
    }
}
