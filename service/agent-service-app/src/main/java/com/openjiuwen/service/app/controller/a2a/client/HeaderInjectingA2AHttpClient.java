/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@link A2AHttpClient} decorator that injects propagation headers (resolved per request
 * via {@link A2APropagationHeaderRegistry}) onto outbound A2A requests. All HTTP I/O still
 * executes in the wrapped client; this class only adds headers through the builders.
 *
 * <p>When no resolver is registered (or it yields no headers), nothing is injected and
 * behavior is identical to the plain client.
 *
 * @since 0.1.2
 */
public class HeaderInjectingA2AHttpClient implements A2AHttpClient {
    private final A2AHttpClient delegate;

    /**
     * Wraps the given client.
     *
     * @param delegate the client executing the actual HTTP I/O
     */
    public HeaderInjectingA2AHttpClient(A2AHttpClient delegate) {
        this.delegate = delegate;
    }

    A2AHttpClient unwrap() {
        return delegate;
    }

    @Override
    public GetBuilder createGet() {
        return new GetWrapper(delegate.createGet());
    }

    @Override
    public PostBuilder createPost() {
        return new PostWrapper(delegate.createPost());
    }

    @Override
    public DeleteBuilder createDelete() {
        return new DeleteWrapper(delegate.createDelete());
    }

    private abstract static class BaseWrapper<B extends A2AHttpClient.Builder<B>> {
        final B delegate;
        private boolean isInjected;

        BaseWrapper(B delegate) {
            this.delegate = delegate;
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

        GetWrapper(A2AHttpClient.GetBuilder delegate) {
            super(delegate);
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
        private String url;
        private String body;

        PostWrapper(A2AHttpClient.PostBuilder delegate) {
            super(delegate);
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
            return delegate.post();
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) throws IOException, InterruptedException {
            inject(body);
            return delegate.postAsyncSSE(onEvent, onError, onComplete);
        }

        @Override
        A2AHttpClient.PostBuilder self() {
            return this;
        }
    }

    private static final class DeleteWrapper
            extends BaseWrapper<A2AHttpClient.DeleteBuilder> implements A2AHttpClient.DeleteBuilder {
        private String url;

        DeleteWrapper(A2AHttpClient.DeleteBuilder delegate) {
            super(delegate);
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
