/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for the outbound A2A propagation-header providers. Consumers (for example an
 * observability feature) register providers that compute propagation headers per
 * request; {@link HeaderInjectingA2AHttpClient} invokes them at request-execution time.
 *
 * <p>The registry is deliberately provider-shaped rather than a static key/value table:
 * values such as W3C {@code traceparent} are dynamic per request and must be computed
 * from the request coordinates at send time.</p>
 *
 * <p>Multiple providers may be registered concurrently: providers of different
 * classes coexist (registering appends), while re-registering a provider whose class
 * is already present replaces that earlier instance — the newest registration stands
 * in. Modules owning disjoint header domains therefore coexist without ordering
 * agreements, and several application contexts sharing one JVM (each instantiating
 * the same module) collapse onto one live registration per provider class instead of
 * stacking duplicates. Registration, same-class replacement and clearing are
 * serialized on one lock, so concurrent registrations of one class always collapse
 * onto one live instance. Passing {@code null} clears every registered provider and
 * disables injection again. The returned {@link Registration} removes only its own
 * entry on close, so an outdated handle cannot remove a newer registration.</p>
 *
 * <p>When no provider is registered, {@link #provide(A2AOutboundRequest)} yields an
 * empty map and no header is injected, so behavior is identical to the plain client
 * (opt-in only).</p>
 *
 * <p>Provider contract:
 * <ul>
 *   <li>Providers are consulted in registration order and their headers merged into
 *   one map, with header names compared case-insensitively. A header name
 *   contributed by two different providers fails the request before any HTTP I/O
 *   ({@link IllegalStateException}): header namespaces are disjoint by owning
 *   domain (observation headers, security headers, ...), so a collision is a
 *   wiring mistake that must surface instead of silently resolving.
 *   To swap in a different provider class, close the earlier {@link Registration}
 *   first and then register the new provider; re-registering the same provider class
 *   replaces the earlier instance directly.</li>
 *   <li>The provider receives the outbound A-&gt;B request coordinates as an
 *   {@link A2AOutboundRequest} — not the inbound user-to-runtime request.</li>
 *   <li>{@link A2APropagationHeaderProvider#capture()} runs before the remote call is
 *   scheduled. Every registered provider is captured and the captured list is bound
 *   to the invocation. A provider may capture the caller's current propagation state
 *   and return a provider bound to that invocation; a {@code capture()} returning
 *   null keeps the live instance bound. Its headers are then computed on
 *   the I/O worker for the call and its retries, and the binding is restored on exit.
 *   The default capture method returns the original provider, preserving existing
 *   behavior. Header computation must be thread-safe; uncaptured caller-thread state
 *   such as MDC, OpenTelemetry context or Spring request context is unavailable on the
 *   I/O worker.</li>
 *   <li>Provider headers are added after SDK-set headers via
 *   {@code addHeader}; the outcome for a same-named header is defined by the underlying
 *   HTTP client implementation (the JDK client keeps the last written value; custom
 *   clients may append or reject duplicates). Providers should restrict themselves to
 *   propagation-style headers (for example {@code traceparent}, {@code tracestate},
 *   {@code baggage}, {@code tenant-id}, {@code request-id}) and must not override
 *   protocol or credential headers such as {@code Content-Type}, {@code A2A-Version},
 *   {@code Host}, {@code Content-Length}, {@code Cookie} or {@code Authorization}.</li>
 *   <li>Exceptions propagate (fail-closed): a {@code RuntimeException} from any
 *   provider fails the request before any HTTP I/O. Pure-observability providers should
 *   handle their own errors and return an empty map instead of throwing.</li>
 * </ul>
 *
 * @since 0.1.2
 */
public final class A2APropagationHeaderRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2APropagationHeaderRegistry.class);

    private static final Object LOCK = new Object();
    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<List<Entry>> INVOCATION = new ThreadLocal<>();

    private A2APropagationHeaderRegistry() {
    }

    /**
     * One live registration slot: the owning handle plus the provider it registered.
     * Removal matches the handle, so neither re-registration nor a value-equal
     * provider can remove another registration's entry through {@code equals()}.
     */
    private record Entry(Registration registration, A2APropagationHeaderProvider provider) {
    }

    /**
     * Registers the propagation-header provider. Providers of different classes
     * coexist (append); re-registering a provider whose class is already registered
     * replaces that earlier instance (the newest stands in — several application
     * contexts in one JVM each register their instance of the same module, and
     * concurrent registrations of one class are serialized so exactly one instance
     * stays live). Passing {@code null} clears every registered provider and
     * disables injection again.
     *
     * @param headerProvider provider computing headers per request; may be null
     * @return handle for this registration; closing it removes only this registration
     */
    public static Registration registerProvider(A2APropagationHeaderProvider headerProvider) {
        synchronized (LOCK) {
            if (headerProvider == null) {
                PROVIDERS.clear();
                return new Registration();
            }
            Registration registration = new Registration();
            Class<?> providerClass = headerProvider.getClass();
            boolean replacedEarlier = PROVIDERS.removeIf(entry -> entry.provider().getClass() == providerClass);
            if (replacedEarlier) {
                LOGGER.warn("propagation header provider class {} re-registered: the earlier instance was"
                        + " replaced and the newest stands in (multiple application contexts sharing one"
                        + " JVM collapse onto one live registration per provider class); unexpected here?"
                        + " check the wiring", providerClass.getName());
            }
            PROVIDERS.add(new Entry(registration, headerProvider));
            return registration;
        }
    }

    /**
     * Computes the headers to inject for one request by consulting every registered
     * provider in registration order and merging their headers.
     *
     * @param request the outbound request coordinates
     * @return headers to inject (empty when no provider is registered or all of them
     *         return null)
     * @throws IllegalStateException when two providers contribute the same header
     *         name (header names are compared case-insensitively)
     */
    public static Map<String, String> provide(A2AOutboundRequest request) {
        List<Entry> current = INVOCATION.get();
        if (current == null) {
            current = PROVIDERS;
        }
        if (current.isEmpty()) {
            return Map.of();
        }
        Map<String, String> merged = null;
        for (Entry slot : current) {
            Map<String, String> headers = slot.provider().headersFor(request);
            if (headers == null || headers.isEmpty()) {
                continue;
            }
            if (merged == null) {
                merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    throw new IllegalStateException("propagation header '" + entry.getKey()
                            + "' is contributed by more than one provider; header namespaces"
                            + " must be disjoint per owning domain (names are case-insensitive)");
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged == null ? Map.of() : merged;
    }

    /**
     * Captures every registered provider before scheduling; restores the worker's
     * prior binding even on failure or interruption.
     *
     * @param <T> action result type
     * @param action invocation to execute with the captured providers
     * @return invocation with worker binding restoration
     */
    static <T> Callable<T> captureInvocation(Callable<T> action) {
        List<Entry> current = INVOCATION.get();
        if (current == null) {
            current = PROVIDERS;
        }
        if (current.isEmpty()) {
            return action;
        }
        List<Entry> captured = new ArrayList<>(current.size());
        for (Entry slot : current) {
            A2APropagationHeaderProvider live = slot.provider();
            A2APropagationHeaderProvider bound = live.capture();
            captured.add(new Entry(slot.registration(), bound != null ? bound : live));
        }
        List<Entry> snapshot = List.copyOf(captured);
        return () -> {
            List<Entry> previous = INVOCATION.get();
            INVOCATION.set(snapshot);
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
     * Handle for one provider registration. {@link #close()} removes this handle's
     * registration only while it is still the live one: re-registering the same
     * provider class replaces the earlier entry together with its handle, so an
     * outdated handle (or a value-equal provider) cannot remove a newer registration.
     *
     * @since 0.1.2
     */
    public static final class Registration implements AutoCloseable {
        private Registration() {
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                PROVIDERS.removeIf(entry -> entry.registration() == this);
            }
        }
    }
}
