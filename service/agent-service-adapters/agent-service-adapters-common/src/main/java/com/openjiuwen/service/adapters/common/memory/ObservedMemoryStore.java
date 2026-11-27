/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Decorates search without changing the other store operations.
 *
 * @since 0.1.5
 */
public final class ObservedMemoryStore implements MemoryStore {
    private final MemoryStore delegate;
    private final MemorySearchObserver observer;

    private ObservedMemoryStore(MemoryStore delegate, MemorySearchObserver observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    /**
     * Attaches an observer if one is configured.
     *
     * @param store original provider
     * @param observer optional search observer
     * @return decorated store, or the original store when observation is disabled
     */
    public static MemoryStore wrap(MemoryStore store, MemorySearchObserver observer) {
        return observer == null ? store : new ObservedMemoryStore(store, observer);
    }

    /** Isolates observer failures for callers using a transport directly; already protected handles are reused. */
    public static MemorySearchObserver.Call protect(MemorySearchObserver.Call call) {
        return call == null || call instanceof SafeCall ? call : new SafeCall(call);
    }

    @Override
    public List<MemoryRecord> search(MemorySearchRequest request) {
        Optional<MemorySearchObserver.Call> call = safely(() -> observer.start(delegate.getProvider(),
            request == null ? null : request.query(), request == null ? 0 : request.topK()));
        if (call.isEmpty()) {
            return delegate.search(request);
        }
        SafeCall safeCall = new SafeCall(call.orElseThrow());
        List<MemoryRecord> result = invoke(() -> delegate instanceof MemorySearchObserver.Transport transport
            ? transport.search(request, safeCall) : delegate.search(request), failure -> {
            String code = failure instanceof ExternalSvcAdapterException external && external.getErrorCode() != null
                ? external.getErrorCode().getCode()
                : failure.getClass().getSimpleName();
            safeCall.failed(code);
            throw failure;
        });
        if (!safeCall.isSkipped) {
            safeCall.succeeded(result == null ? null : Collections.unmodifiableList(result));
        }
        return result;
    }

    @Override
    public String getProvider() {
        return delegate.getProvider();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public MemoryWriteResult add(MemoryAddRequest request) {
        return delegate.add(request);
    }

    @Override
    public Optional<MemoryRecord> get(MemoryGetRequest request) {
        return delegate.get(request);
    }

    @Override
    public void delete(MemoryDeleteRequest request) {
        delegate.delete(request);
    }

    private static <T> Optional<T> safely(Supplier<T> callback) {
        return invoke(() -> Optional.ofNullable(callback.get()), failure -> Optional.empty());
    }

    /**
     * Runs an extension on the caller thread. Reading the already finished task preserves
     * the original cause, including runtime exceptions defined by third-party providers.
     *
     * @param <T> result type
     * @param callback operation to execute exactly once
     * @param onFailure policy for an unchecked exception; errors are never handled by this policy
     * @return operation result or the policy's fallback
     */
    private static <T> T invoke(Supplier<T> callback, Function<RuntimeException, T> onFailure) {
        FutureTask<T> task = new FutureTask<>(callback::get);
        task.run();
        try {
            return task.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException failure) {
                return onFailure.apply(failure);
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException("Unexpected wait for a completed callback", interrupted);
        }
    }

    private static final class SafeCall implements MemorySearchObserver.Call {
        private final MemorySearchObserver.Call delegate;
        private boolean isSkipped;

        private SafeCall(MemorySearchObserver.Call delegate) {
            this.delegate = delegate;
        }

        @Override
        public void effectiveTopK(int topK) {
            run(() -> delegate.effectiveTopK(topK));
        }

        @Override
        public void skipped() {
            isSkipped = true;
            run(delegate::skipped);
        }

        @Override
        public MemorySearchObserver.TraceHeaders startAttempt(int attempt) {
            return safely(() -> delegate.startAttempt(attempt)).orElse(MemorySearchObserver.TraceHeaders.NONE);
        }

        @Override
        public void endAttempt(int attempt, boolean isCompleted) {
            run(() -> delegate.endAttempt(attempt, isCompleted));
        }

        @Override
        public void rawResponse(int acceptedAttempt, String json) {
            run(() -> delegate.rawResponse(acceptedAttempt, json));
        }

        @Override
        public void succeeded(List<MemoryRecord> records) {
            run(() -> delegate.succeeded(records));
        }

        @Override
        public void failed(String code) {
            run(() -> delegate.failed(code));
        }

        private static void run(Runnable callback) {
            invoke(() -> {
                callback.run();
                return true;
            }, failure -> false);
        }
    }
}
