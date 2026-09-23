/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.A2AMessageContext;
import com.openjiuwen.service.app.controller.a2a.A2aJsonRpcDispatcher;
import com.openjiuwen.service.app.hosting.HostedIngressResolver;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;

/** Default method binding. Execution and Task ownership remain in the shared dispatcher and SDK. */
public final class DefaultA2aRuntimeInvoker implements A2aRuntimeInvoker,
        ApplicationListener<ContextClosedEvent>, ApplicationContextAware, Ordered {
    private final A2aJsonRpcDispatcher dispatcher;
    private final A2AProperties properties;
    private final ObjectProvider<AgentReadiness> readiness;
    private final ObjectProvider<HostedIngressResolver> hosted;
    private volatile boolean closed;
    private ApplicationContext applicationContext;

    public DefaultA2aRuntimeInvoker(A2aJsonRpcDispatcher dispatcher, A2AProperties properties,
            ObjectProvider<AgentReadiness> readiness, ObjectProvider<HostedIngressResolver> hosted) {
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.readiness = readiness;
        this.hosted = hosted;
    }

    @Override
    public Flow.Publisher<String> invoke(A2aInvocationRequest request) {
        Objects.requireNonNull(request, "request");
        InvocationCallContext context = new InvocationCallContext();
        context.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        Map<String, String> identity = new LinkedHashMap<>();
        for (String name : Set.of("x-user-id", "x-space-id", "x-tenant-id")) {
            String value = request.headers().get(name);
            if (value != null && !value.isBlank()) {
                identity.put(name, value);
            }
        }
        if (!identity.isEmpty()) {
            context.getState().put(A2AMessageContext.INGRESS_HEADERS_STATE_KEY, Map.copyOf(identity));
        }
        long max = properties.getMaxMessageBytes();
        if (max >= 0 && request.jsonRpcRequest() != null
                && request.jsonRpcRequest().getBytes(StandardCharsets.UTF_8).length > max) {
            return InvocationPublisher.single(A2aJsonRpcDispatcher.errorResponse(null,
                    new InvalidRequestError("Request too large")).json(), context);
        }
        AgentReadiness state = readiness.getIfAvailable();
        if (closed || (hosted.getIfAvailable() == null
                && (state == null || !state.isProcessUp() || !state.isAgentLoaded()))) {
            return InvocationPublisher.single(A2aJsonRpcDispatcher.reject(request.jsonRpcRequest(),
                    new InternalError("Agent is not ready")).json(), context);
        }
        var result = dispatcher.dispatch(request.jsonRpcRequest(), new A2aJsonRpcDispatcher.DispatchContext(
                request.agentId(), context, target -> { }));
        if (result instanceof A2aJsonRpcDispatcher.Single single) {
            return InvocationPublisher.single(single.json(), context);
        }
        var stream = (A2aJsonRpcDispatcher.Stream) result;
        return new InvocationPublisher<>(stream.publisher(),
                event -> A2aJsonRpcDispatcher.streamingResponse(stream.requestId(), event), context);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (applicationContext == null || event.getApplicationContext() == applicationContext) {
            closed = true;
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** Remembers cancellation even when the SDK registers its consumer after cancellation. */
    static final class InvocationCallContext extends ServerCallContext {
        private boolean cancelled;
        private Runnable callback;

        InvocationCallContext() {
            super(UnauthenticatedUser.INSTANCE, Map.of(), Set.of());
        }

        @Override
        public void setEventConsumerCancelCallback(Runnable value) {
            Runnable run = null;
            synchronized (this) {
                if (cancelled) {
                    run = value;
                } else {
                    callback = value;
                }
            }
            if (run != null) {
                run.run();
            }
        }

        @Override
        public void invokeEventConsumerCancelCallback() {
            Runnable run;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                run = callback;
                callback = null;
            }
            if (run != null) {
                run.run();
            }
        }
    }
}
