/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional protocol ingress extension; empty preserves the original execution path. */
@FunctionalInterface
public interface A2AIngressHook {
    /**
     * Handles a validated request without starting a business execution.
     * Implementations must enforce target/session ownership and bound their work.
     *
     * @param params original message, never rewritten by the ingress
     * @param context server call context with ingress identity headers
     * @param agentId selected hosted agent, or null for the legacy default runtime
     * @return independent receipt Task, or empty to continue the original request
     */
    Optional<Task> handle(MessageSendParams params, ServerCallContext context, String agentId);
    /** Checks that a direct receipt cannot masquerade as a transition of the business task. */
    static void validateReceipt(MessageSendParams params, Task receipt) {
        if (receipt.id() == null || receipt.id().isBlank() || Objects.equals(receipt.id(), params.message().taskId())
                || !Objects.equals(receipt.contextId(), params.message().contextId()) || receipt.status() == null
                || !receipt.status().state().isFinal()) {
            throw new IllegalStateException("Ingress receipt must be an independent final Task in the same context");
        }
    }

    /** A single Task snapshot is a valid streaming event; no business execution is started. */
    static Flow.Publisher<StreamingEventKind> receiptPublisher(Task receipt) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean sent = new AtomicBoolean();
            private volatile boolean cancelled;

            @Override
            public void request(long count) {
                if (cancelled || !sent.compareAndSet(false, true)) {
                    return;
                }
                if (count <= 0) {
                    subscriber.onError(new IllegalArgumentException("Positive demand required"));
                    return;
                }
                subscriber.onNext(receipt);
                if (!cancelled) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
