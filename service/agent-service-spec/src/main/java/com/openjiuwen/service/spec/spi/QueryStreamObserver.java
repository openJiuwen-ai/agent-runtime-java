/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryChunk;

/**
 * Streaming callback for Query execution (pure Java, no Reactor types in spec).
 *
 * @since 0.1.0
 */
public interface QueryStreamObserver {
    /**
     * Receives the next stream chunk.
     *
     * @param chunk the query chunk
     */
    void onNext(QueryChunk chunk);

    /**
     * Notifies a stream failure.
     *
     * @param error the failure cause
     */
    void onError(Throwable error);

    /**
     * Notifies successful stream completion.
     */
    void onComplete();

    /**
     * Returns whether the stream has been cancelled by the client.
     *
     * @return {@code true} when cancelled
     */
    default boolean isCancelled() {
        return false;
    }
}
