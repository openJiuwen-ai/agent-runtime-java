/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryChunk;

/**
 * Streaming callback for Query execution (pure Java, no Reactor types in spec).
 */
public interface QueryStreamObserver {

    void onNext(QueryChunk chunk);

    void onError(Throwable error);

    void onComplete();

    default boolean isCancelled() {
        return false;
    }
}
