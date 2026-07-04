/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation token for an active streaming query on a conversation.
 *
 * @since 0.1.0
 */
public final class StreamCancellationHandle {
    private final String conversationId;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    StreamCancellationHandle(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * Returns the conversation identifier for this handle.
     *
     * @return the conversation identifier
     */
    public String getConversationId() {
        return conversationId;
    }

    /** Marks this stream handle as cancelled. */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
