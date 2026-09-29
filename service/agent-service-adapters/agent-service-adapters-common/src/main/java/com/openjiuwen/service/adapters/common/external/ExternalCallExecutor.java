/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes external service calls with timeout, retry, circuit breaker, and audit handling.
 *
 * @since 2026-06-24
 */
public class ExternalCallExecutor {
    private static final Logger log = LoggerFactory.getLogger(ExternalCallExecutor.class);

    private static final String AUDIT_MARKER = "EXTERNAL_CALL_AUDIT";

    private static final ExecutorService DEFAULT_TIMEOUT_EXECUTOR = newTimeoutExecutor();

    private final String adapterType;

    private final String targetId;

    private final ExternalCallPolicy policy;

    private final ExternalSvcAdapterErrorCode outboundFailureCode;

    private final ExternalSvcAdapterErrorCode circuitOpenCode;

    private final ExternalSvcAdapterErrorCode retryInterruptedCode;

    private final ExternalSvcAdapterErrorCode timeoutCode;

    private final ExecutorService timeoutExecutor;

    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();

    public ExternalCallExecutor(String adapterType, String targetId, ExternalCallPolicy policy,
        ExternalSvcAdapterErrorCode outboundFailureCode, ExternalSvcAdapterErrorCode circuitOpenCode,
        ExternalSvcAdapterErrorCode retryInterruptedCode, ExternalSvcAdapterErrorCode timeoutCode) {
        this(adapterType, targetId, policy, outboundFailureCode, circuitOpenCode, retryInterruptedCode, timeoutCode,
            DEFAULT_TIMEOUT_EXECUTOR);
    }

    ExternalCallExecutor(String adapterType, String targetId, ExternalCallPolicy policy,
        ExternalSvcAdapterErrorCode outboundFailureCode, ExternalSvcAdapterErrorCode circuitOpenCode,
        ExternalSvcAdapterErrorCode retryInterruptedCode, ExternalSvcAdapterErrorCode timeoutCode,
        ExecutorService timeoutExecutor) {
        this.adapterType = adapterType != null && !adapterType.isBlank() ? adapterType : "external";
        this.targetId = targetId != null && !targetId.isBlank() ? targetId : "default";
        this.policy = policy != null ? policy : new DefaultExternalCallPolicy();
        validatePolicy(this.policy);
        this.outboundFailureCode = outboundFailureCode;
        this.circuitOpenCode = circuitOpenCode;
        this.retryInterruptedCode = retryInterruptedCode;
        this.timeoutCode = timeoutCode;
        this.timeoutExecutor = timeoutExecutor != null ? timeoutExecutor : DEFAULT_TIMEOUT_EXECUTOR;
    }

    /**
     * Executes one external service operation under the configured call policy.
     *
     * @param <T> operation result type
     * @param operationType external operation type
     * @param method external operation method
     * @param shouldRetry whether retry policy applies to this operation
     * @param callable external operation callback
     * @return operation result returned by the callback
     */
    public <T> T execute(String operationType, String method, boolean shouldRetry, Callable<T> callable) {
        return execute(operationType, method, shouldRetry, null, callable);
    }

    /**
     * Executes one external service operation under the configured call policy.
     *
     * @param <T> operation result type
     * @param operationType external operation type
     * @param method external operation method
     * @param shouldRetry whether retry policy applies to this operation
     * @param request request object used for audit logging
     * @param callable external operation callback
     * @return operation result returned by the callback
     */
    public <T> T execute(String operationType, String method, boolean shouldRetry, Object request,
        Callable<T> callable) {
        String circuitKey = operationType + "." + method;
        int maxAttempts = shouldRetry ? 1 + policy.getRetry().getMax() : 1;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ensureCircuitClosed(circuitKey, operationType, method);
            long startNanos = System.nanoTime();
            try {
                T result = callWithTimeout(callable, operationType, method);
                recordSuccess(circuitKey);
                auditSuccess(operationType, method, attempt, elapsedMs(startNanos), request, result);
                return result;
            } catch (ExternalSvcAdapterException | IllegalArgumentException | IllegalStateException
                     | UnsupportedOperationException ex) {
                lastFailure = ex;
                auditFailure(operationType, method, attempt, elapsedMs(startNanos), request, ex);
                if (attempt >= maxAttempts || isNonRetryableExternalFailure(ex)) {
                    recordFailure(circuitKey);
                    throw asExternalFailure(ex, operationType, method);
                }
                sleepBeforeRetry();
            }
        }
        throw asExternalFailure(lastFailure, operationType, method);
    }

    /**
     * Returns the effective timeout in seconds.
     *
     * @return effective timeout in seconds
     */
    public int timeoutSeconds() {
        return Math.max(1, (int) Math.ceil(policy.getTimeoutMs() / 1000.0d));
    }

    private <T> T callWithTimeout(Callable<T> callable, String operationType, String method) {
        Future<T> future;
        try {
            future = timeoutExecutor.submit(callable);
        } catch (RejectedExecutionException ex) {
            throw new ExternalSvcAdapterException(outboundFailureCode,
                adapterType + " timeout executor rejected call, target=" + targetId + ", method=" + operationType + "."
                    + method, ex);
        }
        try {
            return future.get(policy.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(false);
            throw new ExternalSvcAdapterException(timeoutCode,
                adapterType + " call timed out, target=" + targetId + ", method=" + operationType + "." + method, ex);
        } catch (InterruptedException ex) {
            throw new ExternalSvcAdapterException(retryInterruptedCode,
                adapterType + " call interrupted, target=" + targetId + ", method=" + operationType + "." + method, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void ensureCircuitClosed(String circuitKey, String operationType, String method) {
        if (!policy.getCircuitBreaker().isEnabled()) {
            return;
        }
        CircuitState state = circuitStates.get(circuitKey);
        if (state == null || state.failures < policy.getCircuitBreaker().getFailureThreshold()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - state.lastFailureMs >= policy.getCircuitBreaker().getResetTimeoutMs()) {
            circuitStates.remove(circuitKey);
            return;
        }
        throw new ExternalSvcAdapterException(circuitOpenCode,
            adapterType + " circuit breaker is open, target=" + targetId + ", method=" + operationType + "." + method);
    }

    private void recordSuccess(String circuitKey) {
        circuitStates.remove(circuitKey);
    }

    private void recordFailure(String circuitKey) {
        if (!policy.getCircuitBreaker().isEnabled()) {
            return;
        }
        circuitStates.compute(circuitKey, (ignored, state) -> {
            CircuitState next = state != null ? state : new CircuitState();
            next.failures++;
            next.lastFailureMs = System.currentTimeMillis();
            return next;
        });
    }

    private boolean isNonRetryableExternalFailure(RuntimeException ex) {
        if (ex instanceof ExternalSvcAdapterException adapterException) {
            return adapterException.getErrorCode() == circuitOpenCode
                || adapterException.getErrorCode() == retryInterruptedCode;
        }
        return false;
    }

    private RuntimeException asExternalFailure(RuntimeException ex, String operationType, String method) {
        if (ex instanceof ExternalSvcAdapterException adapterException) {
            return adapterException;
        }
        return new ExternalSvcAdapterException(outboundFailureCode,
            adapterType + " outbound call failed, target=" + targetId + ", method=" + operationType + "." + method, ex);
    }

    private void sleepBeforeRetry() {
        long backoffMs = policy.getRetry().getBackoffMs();
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            throw new ExternalSvcAdapterException(retryInterruptedCode,
                adapterType + " retry interrupted, target=" + targetId, ex);
        }
    }

    private void auditSuccess(String operationType, String method, int attempt, long elapsedMs, Object request,
        Object result) {
        if (!policy.getAudit().isEnabled()) {
            return;
        }
        log.info("{} adapter={}, success=true, target={}, method={}.{}, attempt={}, "
                + "elapsedMs={}, request={}, response={}", AUDIT_MARKER, adapterType, targetId, operationType, method,
            attempt, elapsedMs, summarize(request), summarize(result));
    }

    private void auditFailure(String operationType, String method, int attempt, long elapsedMs, Object request,
        RuntimeException ex) {
        if (!policy.getAudit().isEnabled()) {
            return;
        }
        String code = ex instanceof ExternalSvcAdapterException adapterException ? adapterException.getErrorCode()
            .getCode() : outboundFailureCode.getCode();
        log.warn("{} adapter={}, success=false, code={}, target={}, method={}.{}, attempt={}, "
                + "elapsedMs={}, request={}, error={}", AUDIT_MARKER, adapterType, code, targetId,
            operationType, method, attempt, elapsedMs, summarize(request),
            ex.getClass().getSimpleName() + ":" + Objects.toString(ex.getMessage(), ""));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return "Map(size=" + map.size() + ",hash=" + Integer.toHexString(map.hashCode()) + ")";
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) {
                count++;
                if (count >= 100) {
                    return "Iterable(size>=100)";
                }
            }
            return "Iterable(size=" + count + ")";
        }
        return value.getClass().getSimpleName() + "(hash=" + Integer.toHexString(value.hashCode()) + ")";
    }

    private static ExecutorService newTimeoutExecutor() {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("external-call-timeout",
                    (thread, error) -> log.error("Uncaught external call error thread={}", thread.getName(), error));
        }
        return new ThreadPoolExecutor(0, 64, 100L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void validatePolicy(ExternalCallPolicy policy) {
        if (policy.getTimeoutMs() <= 0) {
            throw new IllegalArgumentException("external timeout-ms must be greater than zero");
        }
        if (policy.getRetry() == null) {
            throw new IllegalArgumentException("external retry policy must not be null");
        }
        if (policy.getCircuitBreaker() == null) {
            throw new IllegalArgumentException("external circuit-breaker policy must not be null");
        }
        if (policy.getAudit() == null) {
            throw new IllegalArgumentException("external audit policy must not be null");
        }
    }

    private static final class CircuitState {
        private int failures;

        private long lastFailureMs;
    }

    private static final class DefaultExternalCallPolicy implements ExternalCallPolicy {
        private final ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private final ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private final ExternalAuditPolicy audit = new ExternalAuditPolicy();

        @Override
        public int getTimeoutMs() {
            return 30000;
        }

        @Override
        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        @Override
        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        @Override
        public ExternalAuditPolicy getAudit() {
            return audit;
        }
    }
}
