/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpClientProvider;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;

/**
 * High-priority test {@link A2AHttpClientProvider} registered via
 * {@code META-INF/services}. It proves that the SDK provider-selection mechanism keeps
 * working end to end: {@code A2AHttpClientFactory.create()} must return the tagging
 * client produced here, and the runtime transport must wrap it instead of bypassing it
 * with a hardcoded JDK client. The tagging client fully delegates to the JDK client,
 * so all other tests observe unchanged HTTP behavior.
 */
public class TestTaggingA2AHttpClientProvider implements A2AHttpClientProvider {
    /**
     * Creates a tagging client that delegates to the JDK client.
     *
     * @return the tagging HTTP client
     */
    @Override
    public A2AHttpClient create() {
        return new TaggingA2AHttpClient(new JdkA2AHttpClient());
    }

    /**
     * Returns a priority above the JDK provider default (0).
     *
     * @return the provider priority
     */
    @Override
    public int priority() {
        return 100;
    }

    /**
     * Returns the provider name.
     *
     * @return the provider name
     */
    @Override
    public String name() {
        return "test-tagging";
    }

    /**
     * Marker client that delegates every operation to the wrapped JDK client, letting
     * tests identify provider-selected instances by type.
     */
    public static final class TaggingA2AHttpClient implements A2AHttpClient {
        private final A2AHttpClient delegate;

        /**
         * Wraps the given client.
         *
         * @param delegate the client executing the actual HTTP I/O
         */
        public TaggingA2AHttpClient(A2AHttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public GetBuilder createGet() {
            return delegate.createGet();
        }

        @Override
        public PostBuilder createPost() {
            return delegate.createPost();
        }

        @Override
        public DeleteBuilder createDelete() {
            return delegate.createDelete();
        }
    }
}
