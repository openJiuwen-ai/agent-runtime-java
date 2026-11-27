/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.List;

/**
 * Optional, trusted memory-search instrumentation. Callbacks must not block or mutate provider data.
 *
 * @since 0.1.5
 */
@FunctionalInterface
public interface MemorySearchObserver {
    /**
     * Capture the caller context before dispatch; return null to skip this search. No credentials are supplied.
     *
     * @param provider memory provider name
     * @param query search query
     * @param requestedTopK requested result limit
     * @return the observation handle, or null to skip this search
     */
    Call start(String provider, String query, int requestedTopK);

    /**
     * Per-search handle, bound to one logical invocation, including all governed attempts.
     */
    interface Call {
        /**
         * Called on the caller before submitting a timed transport attempt.
         *
         * @param attempt one-based attempt number
         * @return propagation headers, or empty headers when not tracing
         */
        default TraceHeaders startAttempt(int attempt) {
            return TraceHeaders.NONE;
        }

        /**
         * Called on the caller after the executor accepts or rejects the attempt, not on a late worker.
         *
         * @param attempt one-based attempt number
         * @param isCompleted whether this attempt completed successfully
         */
        default void endAttempt(int attempt, boolean isCompleted) {
        }

        /**
         * Provider-confirmed search depth; absent for opaque providers.
         *
         * @param topK depth sent to the provider
         */
        default void effectiveTopK(int topK) {
        }

        /**
         * Terminal event when the provider handles an empty query locally without dispatch.
         */
        default void skipped() {
        }

        /**
         * Only the response accepted by the retry/timeout executor, before provider-specific projection.
         *
         * @param acceptedAttempt attempt selected by the executor
         * @param json unmodified response body
         */
        default void rawResponse(int acceptedAttempt, String json) {
        }

        /**
         * Normalized provider records, not an original response envelope. Called on logical success.
         *
         * @param records records returned by the provider
         */
        default void succeeded(List<MemoryRecord> records) {
        }

        /**
         * Logical failure. The code contains neither credentials nor an exception message.
         *
         * @param code sanitized failure code
         */
        default void failed(String code) {
        }
    }

    /**
     * Only these two propagation headers may be written to the outbound request.
     *
     * @param traceparent W3C parent context
     * @param tracestate optional vendor state
     */
    record TraceHeaders(String traceparent, String tracestate) {
        /**
         * No propagation headers to add to the request.
         */
        public static final TraceHeaders NONE = new TraceHeaders("", "");
    }

    /**
     * Optional provider capability for observing individual attempts and a raw response, in addition to records.
     */
    interface Transport {
        /**
         * Searches with a handle for this invocation.
         *
         * @param request search input
         * @param call observation handle
         * @return normalized memory records
         */
        List<MemoryRecord> search(MemorySearchRequest request, Call call);
    }
}
