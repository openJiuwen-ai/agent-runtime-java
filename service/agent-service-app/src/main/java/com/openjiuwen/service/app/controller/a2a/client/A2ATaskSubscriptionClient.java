/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskIdParams;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens the standard A2A {@code SubscribeToTask} HTTP/SSE data channel.
 *
 * <p>Transport adapters may attach request headers for authentication, tracing, or other
 * endpoint-specific concerns. This client does not interpret those headers.
 *
 * @since 0.1.1
 */
public final class A2ATaskSubscriptionClient {
    /**
     * Opens a Task subscription.
     *
     * @param request subscription coordinates
     * @param eventConsumer decoded A2A event consumer
     * @param completionHandler normal SSE completion callback
     * @param errorHandler failed SSE callback
     * @return a local subscription handle
     */
    public TaskSubscription subscribe(TaskSubscriptionRequest request, Consumer<ClientEvent> eventConsumer,
            Runnable completionHandler, Consumer<Throwable> errorHandler) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(eventConsumer, "eventConsumer is required");
        Objects.requireNonNull(completionHandler, "completionHandler is required");
        Objects.requireNonNull(errorHandler, "errorHandler is required");
        Client client = A2AClientSupport.create(agentCard(request.endpointUrl()), true);
        AtomicBoolean active = new AtomicBoolean(true);
        TaskSubscription subscription = new TaskSubscription(active, client);
        ClientCallContext context = new ClientCallContext(Map.of(), request.requestHeaders());
        boolean isSubscribed = false;
        try {
            A2AClientSupport.withApplicationClassLoader(() -> {
                client.subscribeToTask(new TaskIdParams(request.taskId()),
                        List.of((event, ignored) -> {
                            if (active.get()) {
                                eventConsumer.accept(event);
                            }
                        }), failure -> {
                            if (!active.compareAndSet(true, false)) {
                                return;
                            }
                            if (failure == null) {
                                completionHandler.run();
                            } else {
                                errorHandler.accept(failure);
                            }
                        }, context);
                return null;
            });
            isSubscribed = true;
            return subscription;
        } finally {
            if (!isSubscribed) {
                subscription.close();
            }
        }
    }

    private static AgentCard agentCard(String endpointUrl) {
        String endpoint = a2aEndpoint(endpointUrl);
        return AgentCard.builder().name("runtime-task-subscription").description("Runtime A2A task subscription")
                .version("1.0").capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Collections.emptyMap()).securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint, null, "1.0")))
                .url(endpoint).preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    static String a2aEndpoint(String value) {
        String endpoint = require(value, "endpointUrl");
        URI uri = URI.create(endpoint).normalize();
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("endpointUrl must be an absolute HTTP(S) URI");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("endpointUrl must not contain a query or fragment");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/a2a") ? normalized : normalized + "/a2a";
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Coordinates for one standard Task subscription.
     *
     * @param endpointUrl target Runtime origin or A2A endpoint
     * @param taskId target Task identifier
     * @param requestHeaders optional HTTP request headers passed to the A2A transport
     */
    public record TaskSubscriptionRequest(String endpointUrl, String taskId, Map<String, String> requestHeaders) {
        public TaskSubscriptionRequest {
            require(endpointUrl, "endpointUrl");
            require(taskId, "taskId");
            requestHeaders = immutableHeaders(requestHeaders);
        }

        /**
         * Creates a subscription without transport-specific request headers.
         *
         * @param endpointUrl target Runtime origin or A2A endpoint
         * @param taskId target Task identifier
         */
        public TaskSubscriptionRequest(String endpointUrl, String taskId) {
            this(endpointUrl, taskId, Map.of());
        }
    }

    private static Map<String, String> immutableHeaders(Map<String, String> headers) {
        return headers == null || headers.isEmpty()
                ? Map.of()
                : Map.copyOf(headers);
    }

    /** Local handle that suppresses later callbacks after the owning call is complete. */
    public static final class TaskSubscription implements AutoCloseable {
        private final AtomicBoolean active;
        private final Client client;

        private TaskSubscription(AtomicBoolean active, Client client) {
            this.active = active;
            this.client = client;
        }

        @Override
        public void close() {
            active.set(false);
            try {
                client.close();
            } catch (A2AException | IllegalStateException ignored) {
                // The logical subscription is already closed; transport cleanup is best effort.
            }
        }
    }
}
