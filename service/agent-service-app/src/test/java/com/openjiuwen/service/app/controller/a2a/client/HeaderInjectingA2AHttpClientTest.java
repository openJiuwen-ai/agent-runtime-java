/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * HeaderInjectingA2AHttpClient 与 A2APropagationHeaderSupport 的单元测试。
 */
class HeaderInjectingA2AHttpClientTest {

    @AfterEach
    void resetResolver() {
        A2APropagationHeaderSupport.setResolver(null);
    }

    @Test
    void injectsResolvedHeadersOnPost() throws Exception {
        A2APropagationHeaderSupport.setResolver((url, body) -> Map.of("traceparent", "00-abc-def-01"));
        RecordingClient recording = new RecordingClient();
        HeaderInjectingA2AHttpClient client = new HeaderInjectingA2AHttpClient(recording);
        client.createPost().url("http://x/a2a").body("{\"jsonrpc\":\"2.0\"}").post();
        assertThat(recording.headers).containsEntry("traceparent", "00-abc-def-01");
    }

    @Test
    void injectsOnGetAndDelete() throws Exception {
        A2APropagationHeaderSupport.setResolver((url, body) -> Map.of("x-trace", "t1"));
        RecordingClient recording = new RecordingClient();
        HeaderInjectingA2AHttpClient client = new HeaderInjectingA2AHttpClient(recording);
        client.createGet().url("http://x/card").get();
        client.createDelete().url("http://x/task/1").delete();
        assertThat(recording.headers).containsEntry("x-trace", "t1");
    }

    @Test
    void noInjectionWhenNoResolver() throws Exception {
        RecordingClient recording = new RecordingClient();
        HeaderInjectingA2AHttpClient client = new HeaderInjectingA2AHttpClient(recording);
        client.createPost().url("http://x/a2a").body("{}").post();
        assertThat(recording.headers).isEmpty();
    }

    @Test
    void resolverReceivesUrlAndBody() throws Exception {
        Map<String, String> seen = new HashMap<>();
        A2APropagationHeaderSupport.setResolver((url, body) -> {
            seen.put("url", url);
            seen.put("body", body);
            return Map.of("k", "v");
        });
        RecordingClient recording = new RecordingClient();
        HeaderInjectingA2AHttpClient client = new HeaderInjectingA2AHttpClient(recording);
        client.createPost().url("http://x/a2a").body("the-body").post();
        assertThat(seen).containsEntry("url", "http://x/a2a").containsEntry("body", "the-body");
    }

    @Test
    void delegateBuildersStillReceiveAllCalls() throws Exception {
        RecordingClient recording = new RecordingClient();
        HeaderInjectingA2AHttpClient client = new HeaderInjectingA2AHttpClient(recording);
        client.createPost().url("http://x/a2a").addHeader("k", "v")
                .addHeaders(Map.of("k2", "v2")).body("b").post();
        assertThat(recording.url).isEqualTo("http://x/a2a");
        assertThat(recording.headers).containsEntry("k", "v").containsEntry("k2", "v2");
        assertThat(recording.body).isEqualTo("b");
    }

    @Test
    void supportResolvesEmptyWhenUnsetOrNullResult() {
        A2APropagationHeaderSupport.setResolver(null);
        assertThat(A2APropagationHeaderSupport.resolve("u", "b")).isEmpty();
        A2APropagationHeaderSupport.setResolver((url, body) -> null);
        assertThat(A2APropagationHeaderSupport.resolve("u", "b")).isEmpty();
    }

    private static final class RecordingClient implements A2AHttpClient {
        final Map<String, String> headers = new HashMap<>();
        String url;
        String body;

        @Override
        public GetBuilder createGet() {
            return new RecGet(this);
        }

        @Override
        public PostBuilder createPost() {
            return new RecPost(this);
        }

        @Override
        public DeleteBuilder createDelete() {
            return new RecDelete(this);
        }
    }

    private abstract static class RecBase<B extends A2AHttpClient.Builder<B>> {
        final RecordingClient client;

        RecBase(RecordingClient client) {
            this.client = client;
        }

        /**
         * Sets the target url.
         *
         * @param url target url
         * @return this builder
         */
        public B url(String url) {
            client.url = url;
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
            client.headers.put(key, value);
            return self();
        }

        /**
         * Adds multiple request headers.
         *
         * @param headers headers to add
         * @return this builder
         */
        public B addHeaders(Map<String, String> headers) {
            client.headers.putAll(headers);
            return self();
        }

        abstract B self();
    }

    private static final class RecPost extends RecBase<A2AHttpClient.PostBuilder> implements A2AHttpClient.PostBuilder {
        RecPost(RecordingClient client) {
            super(client);
        }

        @Override
        public A2AHttpClient.PostBuilder body(String body) {
            client.body = body;
            return this;
        }

        @Override
        public A2AHttpResponse post() {
            return null;
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        A2AHttpClient.PostBuilder self() {
            return this;
        }
    }

    private static final class RecGet extends RecBase<A2AHttpClient.GetBuilder> implements A2AHttpClient.GetBuilder {
        RecGet(RecordingClient client) {
            super(client);
        }

        @Override
        public A2AHttpResponse get() {
            return null;
        }

        @Override
        public CompletableFuture<Void> getAsyncSSE(Consumer<ServerSentEvent> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        A2AHttpClient.GetBuilder self() {
            return this;
        }
    }

    private static final class RecDelete extends RecBase<A2AHttpClient.DeleteBuilder> implements A2AHttpClient.DeleteBuilder {
        RecDelete(RecordingClient client) {
            super(client);
        }

        @Override
        public A2AHttpResponse delete() {
            return null;
        }

        @Override
        A2AHttpClient.DeleteBuilder self() {
            return this;
        }
    }
}
