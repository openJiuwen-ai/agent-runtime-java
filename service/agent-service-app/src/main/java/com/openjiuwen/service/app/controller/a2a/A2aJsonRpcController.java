/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.hosting.HostedIngressResolver;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC controller for A2A protocol endpoints. Handles {@code SendMessage}, {@code SendStreamingMessage},
 * {@code GetTask}, and {@code SubscribeToTask} methods.
 *
 * @since 0.1.0
 */
@ConditionalOnWebApplication
@ConditionalOnProperty(
        prefix = "openjiuwen.service.http", name = "enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class A2aJsonRpcController {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);

    /**
     * Executor that drives A2A SSE subscription. On JDK 21 each subscription runs on its own
     * virtual thread; on JDK 17 a small daemon platform pool is used. This keeps the A2A
     * streaming path off {@code ForkJoinPool.commonPool()} so that long-lived subscriptions
     * do not starve the common pool and benefit from virtual-thread scaling.
     */
    private static final Executor SSE_EXECUTOR = createSseExecutor();

    private final A2aJsonRpcDispatcher dispatcher;
    private final A2AProperties a2aProperties;

    public A2aJsonRpcController(A2aJsonRpcDispatcher dispatcher, A2AProperties a2aProperties) {
        this.dispatcher = dispatcher;
        this.a2aProperties = a2aProperties;
    }

    @PostMapping({A2AServicePaths.A2A_JSONRPC, A2AServicePaths.A2A_JSONRPC_NO_SLASH,
            A2AServicePaths.HOSTED_AGENT_RPC})
    @AuthorizedResource(resource = "a2a", action = "rpc")
    public ResponseEntity<?> handleJsonRpc(@RequestBody(required = false) String rawBody,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        long maxMessageBytes = a2aProperties.getMaxMessageBytes();
        long contentLength = servletRequest.getContentLengthLong();
        if (maxMessageBytes >= 0 && (contentLength < 0 || contentLength > maxMessageBytes)) {
            return ResponseEntity.status(413).build();
        }
        Object variables = servletRequest.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String agentId = variables instanceof Map<?, ?> paths ? (String) paths.get("agentId") : null;
        var context = new A2aJsonRpcDispatcher.DispatchContext(agentId, buildCallContext(servletRequest),
                target -> HostedIngressResolver.selected(servletRequest, target));
        var result = dispatcher.dispatch(rawBody, context);
        if (result instanceof A2aJsonRpcDispatcher.Stream stream) {
            return streamToSse(stream.publisher(), stream.requestId());
        }
        var single = (A2aJsonRpcDispatcher.Single) result;
        var response = ResponseEntity.status(single.status());
        if (single.jsonContentType()) {
            response.contentType(MediaType.APPLICATION_JSON);
        }
        return response.body(single.httpBody() == null ? single.json() : single.httpBody());
    }

    private ResponseEntity<SseEmitter> streamToSse(Flow.Publisher<StreamingEventKind> publisher, Object requestId) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            /**
             * Called when the subscription is established.
             *
             * @param s the flow subscription
             */
            public void onSubscribe(Flow.Subscription s) {
                sub = s;
                s.request(1);
            }

            /**
             * Called when a new streaming event is received.
             *
             * @param e the streaming event
             */
            public void onNext(StreamingEventKind e) {
                try {
                    String data = A2aJsonRpcDispatcher.streamingResponse(requestId, e);
                    emitter.send(SseEmitter.event().name("jsonrpc").data(data));
                    sub.request(1);
                } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException | java.io.IOException
                        | RuntimeException ex) {
                    log.error("A2A SSE event delivery failed requestId={}", requestId, ex);
                    sub.cancel();
                    emitter.completeWithError(ex);
                }
            }

            /**
             * Called when the stream encounters an error.
             *
             * @param t the error
             */
            public void onError(Throwable t) {
                log.error("A2A SSE publisher failed requestId={}", requestId, t);
                emitter.completeWithError(t);
            }

            /**
             * Called when the stream completes normally.
             */
            public void onComplete() {
                emitter.complete();
            }
        }), SSE_EXECUTOR).whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.error("A2A SSE subscription failed requestId={}", requestId, failure);
                // subscribe() may throw synchronously (e.g. executor rejection) before the
                // subscriber's onError is wired; without this the emitter never completes
                // and the SSE connection hangs until the client times out. Complete on an
                // already-completed emitter is a no-op, so this is safe on all paths.
                emitter.completeWithError(failure);
            }
        });
        emitter.onTimeout(emitter::complete);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private ServerCallContext buildCallContext(jakarta.servlet.http.HttpServletRequest req) {
        var ctx = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                Map.of("remote-addr", req.getRemoteAddr(), "path", req.getRequestURI()), Set.of());
        ctx.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        Map<String, String> ingressHeaders = A2AMessageContext.tenantHeadersFrom(req);
        if (!ingressHeaders.isEmpty()) {
            ctx.getState().put(A2AMessageContext.INGRESS_HEADERS_STATE_KEY, ingressHeaders);
        }
        return ctx;
    }

    /**
     * Builds the SSE subscription executor. On JDK 21+ uses a per-task virtual-thread executor so each
     * A2A streaming subscription runs on its own virtual thread; on JDK 17 falls back to a small
     * daemon platform-thread pool sized to the CPU count. The platform pool is unbounded-queue
     * cached-style: subscriptions are long-lived but few, and a cached pool avoids rejecting under
     * bursty load while still bounding peak threads by the subscription count.
     *
     * @return executor for driving {@code publisher.subscribe(...)}
     */
    private static Executor createSseExecutor() {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("a2a-sse",
                    (thread, error) -> log.error("Uncaught A2A SSE thread={}", thread.getName(), error));
        }
        AtomicInteger idx = new AtomicInteger();
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "a2a-sse-" + idx.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((source, error) ->
                    log.error("Uncaught A2A SSE thread={}", source.getName(), error));
            return thread;
        });
    }
}
