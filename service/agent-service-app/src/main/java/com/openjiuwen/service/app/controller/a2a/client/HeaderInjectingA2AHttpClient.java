/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.a2aproject.sdk.spec.A2AClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link A2AHttpClient} decorator that injects propagation headers (resolved per request
 * via {@link A2APropagationHeaderRegistry}) onto outbound A2A requests. All HTTP I/O still
 * executes in the wrapped client; this class only adds headers through the builders.
 *
 * <p>When no resolver is registered (or it yields no headers), nothing is injected and
 * behavior is identical to the plain client.
 *
 * <p>With a positive first-event timeout, POST-launched SSE streams carry a transport-level
 * first-event watchdog (FEAT-062 P11): if the remote sends no SSE event within the timeout,
 * the watchdog arms a synthesized retryable {@link A2AClientException} into the error
 * callback so the caller's failure classification sees a normal transport fault, and drops
 * any later event of the abandoned stream. The gate transitions are CAS-based, so the
 * expiry racing a first event lets exactly one side win.
 *
 * <p>The same timeout also bounds the synchronous {@code POST} response wait (FEAT-062
 * P11 non-streaming follow-up): a non-streaming {@code SendMessage} blocks inside the
 * synchronous HTTP send before any consumer or backstop can observe it, so {@code post()}
 * runs the delegate send on a dedicated executor and synthesizes the same retryable
 * transport fault when no response arrives within the timeout — a silent candidate then
 * rotates instead of holding the invocation until the global budget expires.
 *
 * @since 0.1.2
 */
public class HeaderInjectingA2AHttpClient implements A2AHttpClient {
    private static final Logger log = LoggerFactory.getLogger(HeaderInjectingA2AHttpClient.class);

    // Bounded-wait executor for synchronous POSTs: one thread per in-flight send,
    // recycled after 60s idle; a timed-out send is cancelled (interrupting the
    // blocking JDK send) so no worker outlives its attempt.
    private static final ExecutorService POST_RESPONSE_EXECUTOR = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS, new SynchronousQueue<>(), postResponseWatchdogThreadFactory());

    private final A2AHttpClient delegate;

    private final Map<String, String> fixedHeaders;

    private final long firstEventTimeoutMillis;

    /**
     * Wraps the given client.
     *
     * @param delegate the client executing the actual HTTP I/O
     */
    public HeaderInjectingA2AHttpClient(A2AHttpClient delegate) {
        this(delegate, Map.of());
    }

    /**
     * Wraps a client and adds fixed headers to every request in addition to
     * the existing per-request propagation headers. The SSE first-event
     * watchdog stays disabled.
     *
     * @param delegate client executing the request
     * @param fixedHeaders headers supplied by the outbound authenticator
     */
    public HeaderInjectingA2AHttpClient(A2AHttpClient delegate, Map<String, String> fixedHeaders) {
        this(delegate, fixedHeaders, 0L);
    }

    /**
     * Wraps a client with fixed headers and a first-event watchdog for SSE
     * streams opened through {@code postAsyncSSE}.
     *
     * @param delegate client executing the request
     * @param fixedHeaders headers supplied by the outbound authenticator
     * @param firstEventTimeoutMillis how long a stream may stay silent before
     *        the watchdog fires; {@code <= 0} disables the watchdog
     */
    public HeaderInjectingA2AHttpClient(A2AHttpClient delegate, Map<String, String> fixedHeaders,
            long firstEventTimeoutMillis) {
        this.delegate = delegate;
        this.fixedHeaders = fixedHeaders == null || fixedHeaders.isEmpty()
                ? Map.of()
                : Map.copyOf(fixedHeaders);
        this.firstEventTimeoutMillis = firstEventTimeoutMillis;
    }

    A2AHttpClient unwrap() {
        return delegate;
    }

    /**
     * Builds the daemon thread factory for the POST response-wait executor:
     * named, daemon, and logging uncaught failures instead of killing the JVM.
     *
     * @return the configured thread factory
     */
    private static ThreadFactory postResponseWatchdogThreadFactory() {
        ThreadFactory base = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = base.newThread(runnable);
            thread.setName("a2a-post-response-watchdog");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((source, error) -> log
                    .error("Uncaught POST response-wait failure on thread={}", source.getName(), error));
            return thread;
        };
    }

    @Override
    public GetBuilder createGet() {
        return new GetWrapper(delegate.createGet(), fixedHeaders);
    }

    @Override
    public PostBuilder createPost() {
        return new PostWrapper(delegate.createPost(), fixedHeaders, firstEventTimeoutMillis);
    }

    @Override
    public DeleteBuilder createDelete() {
        return new DeleteWrapper(delegate.createDelete(), fixedHeaders);
    }

    private abstract static class BaseWrapper<B extends A2AHttpClient.Builder<B>> {
        final B delegate;
        final Map<String, String> fixedHeaders;
        private boolean isInjected;

        BaseWrapper(B delegate, Map<String, String> fixedHeaders) {
            this.delegate = delegate;
            this.fixedHeaders = fixedHeaders;
        }

        /**
         * Sets the target url.
         *
         * @param url target url
         * @return this builder
         */
        public B url(String url) {
            delegate.url(url);
            return self();
        }

        /**
         * Adds a request header.
         *
         * @param key   header name
         * @param value header value
         * @return this builder
         */
        public B addHeader(String key, String value) {
            delegate.addHeader(key, value);
            return self();
        }

        /**
         * Adds multiple request headers.
         *
         * @param headers headers to add
         * @return this builder
         */
        public B addHeaders(Map<String, String> headers) {
            delegate.addHeaders(headers);
            return self();
        }

        void inject(String body) {
            if (isInjected) {
                return;
            }
            A2AOutboundRequest request = new A2AOutboundRequest(currentUrl(), method(), body);
            // 标记在注入成功后才置位：provider 抛异常时同一 builder 重试仍会重新注入
            A2APropagationHeaderRegistry.provide(request)
                    .forEach((key, value) -> delegate.addHeader(key, value));
            fixedHeaders.forEach((key, value) -> delegate.addHeader(key, value));
            isInjected = true;
        }

        String currentUrl() {
            return "";
        }

        abstract String method();

        abstract B self();
    }

    private static final class GetWrapper
            extends BaseWrapper<A2AHttpClient.GetBuilder> implements A2AHttpClient.GetBuilder {
        private String url;

        GetWrapper(A2AHttpClient.GetBuilder delegate, Map<String, String> fixedHeaders) {
            super(delegate, fixedHeaders);
        }

        @Override
        public A2AHttpClient.GetBuilder url(String url) {
            this.url = url;
            return super.url(url);
        }

        @Override
        String currentUrl() {
            return url;
        }

        @Override
        String method() {
            return "GET";
        }

        @Override
        public A2AHttpResponse get() throws IOException, InterruptedException {
            inject(null);
            return delegate.get();
        }

        @Override
        public CompletableFuture<Void> getAsyncSSE(Consumer<ServerSentEvent> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) throws IOException, InterruptedException {
            inject(null);
            return delegate.getAsyncSSE(onEvent, onError, onComplete);
        }

        @Override
        A2AHttpClient.GetBuilder self() {
            return this;
        }
    }

    private static final class PostWrapper
            extends BaseWrapper<A2AHttpClient.PostBuilder> implements A2AHttpClient.PostBuilder {
        private static final Logger log = LoggerFactory.getLogger(PostWrapper.class);

        private final long firstEventTimeoutMillis;
        private String url;
        private String body;

        PostWrapper(A2AHttpClient.PostBuilder delegate, Map<String, String> fixedHeaders,
                long firstEventTimeoutMillis) {
            super(delegate, fixedHeaders);
            this.firstEventTimeoutMillis = firstEventTimeoutMillis;
        }

        @Override
        public A2AHttpClient.PostBuilder url(String url) {
            this.url = url;
            return super.url(url);
        }

        @Override
        String currentUrl() {
            return url;
        }

        @Override
        String method() {
            return "POST";
        }

        @Override
        public A2AHttpClient.PostBuilder body(String body) {
            this.body = body;
            delegate.body(body);
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            inject(body);
            if (firstEventTimeoutMillis <= 0) {
                return delegate.post();
            }
            Future<A2AHttpResponse> pending = POST_RESPONSE_EXECUTOR.submit(() -> delegate.post());
            try {
                return pending.get(firstEventTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expired) {
                // cancel first so the blocking JDK send unblocks, then surface the
                // same retryable transport fault the SSE watchdog synthesizes — the
                // caller's classification rotates to the next candidate (FEAT-062 P11)
                pending.cancel(true);
                log.warn("A2A POST response watchdog fired after {}ms; aborting the request attempt",
                        firstEventTimeoutMillis);
                throw postResponseTimeoutFailure();
            } catch (InterruptedException stopped) {
                pending.cancel(true);
                throw stopped;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (cause instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new A2AClientException("Remote A2A POST failed without a transport failure cause", cause);
            }
        }

        /**
         * Builds the retryable fault for a POST whose response never arrived
         * within the timeout: the {@link SocketTimeoutException} cause keeps the
         * fault in the caller's transient (rotatable) classification, matching
         * the SSE watchdog's synthesized failure.
         *
         * @return the synthesized transport fault
         */
        private static A2AClientException postResponseTimeoutFailure() {
            String message = "Remote agent sent no HTTP response within the configured timeout";
            return new A2AClientException(message, new SocketTimeoutException(message));
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) throws IOException, InterruptedException {
            inject(body);
            if (firstEventTimeoutMillis <= 0) {
                return delegate.postAsyncSSE(onEvent, onError, onComplete);
            }
            FirstEventGate gate = new FirstEventGate();
            Consumer<Throwable> guardedOnError = error -> {
                gate.disarm();
                onError.accept(error);
            };
            Runnable guardedOnComplete = () -> {
                gate.disarm();
                onComplete.run();
            };
            CompletableFuture<Void> stream = delegate.postAsyncSSE(event -> {
                if (gate.pass()) {
                    onEvent.accept(event);
                }
            }, guardedOnError, guardedOnComplete);
            scheduleFirstEventWatchdog(gate, stream, onError);
            return stream;
        }

        /**
         * Arms the transport-level first-event watchdog for one stream: when the
         * gate has seen no event within the timeout, a retryable transport fault
         * is synthesized into the error callback FIRST so the caller's existing
         * failure classification can rotate to the next candidate (FEAT-062
         * P11), and only then is the stream cancelled — the cancellation
         * surfaces synchronously as its own stream error, which loses the
         * caller's first-failure race and stays inert.
         *
         * @param gate the per-stream first-event gate
         * @param stream the underlying stream future to cancel on expiry
         * @param onError the raw error callback receiving the synthesized fault
         */
        private void scheduleFirstEventWatchdog(FirstEventGate gate, CompletableFuture<Void> stream,
                Consumer<Throwable> onError) {
            Executor delayedExecutor = CompletableFuture.delayedExecutor(firstEventTimeoutMillis,
                    TimeUnit.MILLISECONDS);
            CompletableFuture.runAsync(() -> {
                if (!gate.expire()) {
                    return;
                }
                log.warn("A2A SSE stream first-event watchdog fired after {}ms; aborting the stream attempt",
                        firstEventTimeoutMillis);
                onError.accept(firstEventTimeoutFailure());
                stream.cancel(true);
            }, delayedExecutor);
        }

        private static Throwable firstEventTimeoutFailure() {
            String message = "Remote agent sent no first SSE event within the configured first-event timeout";
            return new A2AClientException(message, new SocketTimeoutException(message));
        }

        @Override
        A2AHttpClient.PostBuilder self() {
            return this;
        }
    }

    /**
     * One-shot gate deciding the race between a stream's first event and its
     * watchdog expiry: both sides transition the same CAS state, so exactly one
     * of them wins and the loser's effect is skipped. {@code disarm} covers the
     * remaining outcomes (real error, clean completion) that retire the
     * watchdog without expiring it. Once the first event wins, subsequent
     * events continue to pass through the gate.
     */
    private static final class FirstEventGate {
        private static final int WAITING = 0;

        private static final int PASSED = 1;

        private static final int EXPIRED = 2;

        private static final int DISARMED = 3;

        private final AtomicInteger state = new AtomicInteger(WAITING);

        boolean pass() {
            return state.compareAndSet(WAITING, PASSED) || state.get() == PASSED;
        }

        boolean expire() {
            return state.compareAndSet(WAITING, EXPIRED);
        }

        void disarm() {
            state.compareAndSet(WAITING, DISARMED);
        }
    }

    private static final class DeleteWrapper
            extends BaseWrapper<A2AHttpClient.DeleteBuilder> implements A2AHttpClient.DeleteBuilder {
        private String url;

        DeleteWrapper(A2AHttpClient.DeleteBuilder delegate, Map<String, String> fixedHeaders) {
            super(delegate, fixedHeaders);
        }

        @Override
        public A2AHttpClient.DeleteBuilder url(String url) {
            this.url = url;
            return super.url(url);
        }

        @Override
        String currentUrl() {
            return url;
        }

        @Override
        String method() {
            return "DELETE";
        }

        @Override
        public A2AHttpResponse delete() throws IOException, InterruptedException {
            inject(null);
            return delegate.delete();
        }

        @Override
        A2AHttpClient.DeleteBuilder self() {
            return this;
        }
    }
}
