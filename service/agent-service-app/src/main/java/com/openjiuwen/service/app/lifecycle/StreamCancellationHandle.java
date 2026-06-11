package com.openjiuwen.service.app.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation token for an active streaming query on a conversation.
 */
public final class StreamCancellationHandle {

    private final String conversationId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    StreamCancellationHandle(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
