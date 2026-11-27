package com.openjiuwen.service.adapters.common.memory;

import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Decorates search only; no observer means the original provider instance is returned unchanged. */
public final class ObservedMemoryStore implements MemoryStore {
    private final MemoryStore delegate;
    private final MemorySearchObserver observer;

    private ObservedMemoryStore(MemoryStore delegate, MemorySearchObserver observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    public static MemoryStore wrap(MemoryStore store, MemorySearchObserver observer) {
        return observer == null ? store : new ObservedMemoryStore(store, observer);
    }

    @Override
    public List<MemoryRecord> search(MemorySearchRequest request) {
        MemorySearchObserver.Call call = safely(() -> observer.start(delegate.getProvider(),
            request == null ? null : request.query(), request == null ? 0 : request.topK()));
        if (call == null) {
            return delegate.search(request);
        }
        SafeCall safeCall = new SafeCall(call);
        List<MemoryRecord> result;
        try {
            result = delegate instanceof MemorySearchObserver.Transport transport
                ? transport.search(request, safeCall) : delegate.search(request);
        } catch (RuntimeException failure) {
            String code = failure instanceof ExternalSvcAdapterException external && external.getErrorCode() != null
                ? external.getErrorCode().getCode()
                : failure.getClass().getSimpleName();
            safeCall.failed(code);
            throw failure;
        }
        safeCall.succeeded(result == null ? null : Collections.unmodifiableList(result));
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

    private static <T> T safely(Supplier<T> callback) {
        try {
            return callback.get();
        } catch (RuntimeException instrumentationFailure) {
            return null;
        }
    }

    private record SafeCall(MemorySearchObserver.Call delegate) implements MemorySearchObserver.Call {
        @Override
        public MemorySearchObserver.TraceHeaders startAttempt(int attempt) {
            return safely(() -> delegate.startAttempt(attempt));
        }

        @Override
        public void endAttempt(int attempt, boolean completed) {
            run(() -> delegate.endAttempt(attempt, completed));
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
            safely(() -> {
                callback.run();
                return null;
            });
        }
    }
}
