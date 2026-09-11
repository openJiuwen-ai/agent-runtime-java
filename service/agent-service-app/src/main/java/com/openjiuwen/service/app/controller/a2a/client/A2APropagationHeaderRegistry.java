/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Map;
import java.util.concurrent.Callable;
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
 *   <li>{@link A2APropagationHeaderProvider#capture()} runs before the remote call is
 *   scheduled. A provider may capture the caller's current propagation state and
 *   return a provider bound to that invocation. Its headers are then computed on the
 *   I/O worker for the call and its retries, and the binding is restored on exit.
 *   The default capture method returns the original provider, preserving existing
 *   behavior. Header computation must be thread-safe; uncaptured caller-thread state
 *   such as MDC, OpenTelemetry context or Spring request context is unavailable on
 *   the I/O worker.</li>
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
    private static final ThreadLocal<A2APropagationHeaderProvider> INVOCATION = new ThreadLocal<>();

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
        A2APropagationHeaderProvider current = INVOCATION.get();
        if (current == null) {
            current = PROVIDER.get();
        }
        if (current == null) {
            return Map.of();
        }
        Map<String, String> headers = current.headersFor(request);
        return headers != null ? headers : Map.of();
    }

    /**
     * Captures before scheduling; restores the worker's prior binding even on failure or interruption.
     *
     * @param <T> action result type
     * @param action invocation to execute with the captured provider
     * @return invocation with worker binding restoration
     */
    static <T> Callable<T> captureInvocation(Callable<T> action) {
        A2APropagationHeaderProvider provider = INVOCATION.get();
        if (provider == null) {
            provider = PROVIDER.get();
        }
        if (provider == null) {
            return action;
        }
        A2APropagationHeaderProvider captured = provider.capture();
        return () -> {
            A2APropagationHeaderProvider previous = INVOCATION.get();
            INVOCATION.set(captured);
            try {
                return action.call();
            } finally {
                if (previous == null) {
                    INVOCATION.remove();
                } else {
                    INVOCATION.set(previous);
                }
            }
        };
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
