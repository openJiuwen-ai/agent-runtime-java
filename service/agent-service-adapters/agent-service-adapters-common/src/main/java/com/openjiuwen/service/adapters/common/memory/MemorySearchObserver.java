package com.openjiuwen.service.adapters.common.memory;

import java.util.List;

/** Optional, trusted memory-search instrumentation. Callbacks must not block or mutate provider data. */
@FunctionalInterface
public interface MemorySearchObserver {
    /** Capture the caller context before dispatch; return null to skip this search. No credentials are supplied. */
    Call start(String provider, String query, int requestedTopK);

    /** Per-search handle, shared with transport workers. Late attempts must not affect another search. */
    interface Call {
        /** Called on a transport worker, not necessarily the thread that started this search. */
        default TraceHeaders startAttempt(int attempt) {
            return null;
        }

        /** Transport finished; this may occur after the caller timed out. */
        default void endAttempt(int attempt, boolean completed) {
        }

        /** Only the response accepted by the retry/timeout executor, before provider-specific projection. */
        default void rawResponse(int acceptedAttempt, String json) {
        }

        /** Normalized provider records, not an original response envelope. Called on logical success. */
        default void succeeded(List<MemoryRecord> records) {
        }

        /** Logical failure. The code contains neither credentials nor an exception message. */
        default void failed(String code) {
        }
    }

    /** Only these two propagation headers may be written to the outbound request. */
    record TraceHeaders(String traceparent, String tracestate) {
    }

    /** Optional provider capability for observing individual attempts and a raw response, in addition to records. */
    interface Transport {
        List<MemoryRecord> search(MemorySearchRequest request, Call call);
    }
}
