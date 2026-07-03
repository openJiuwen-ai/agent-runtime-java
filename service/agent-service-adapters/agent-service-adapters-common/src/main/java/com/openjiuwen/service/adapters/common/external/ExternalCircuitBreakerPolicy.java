/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * Circuit-breaker policy for external service calls.
 *
 * @since 2026-06-24
 */
public class ExternalCircuitBreakerPolicy {
    private boolean isEnabled = false;

    private int failureThreshold = 5;

    private long resetTimeoutMs = 30000L;

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("circuit-breaker.failure-threshold must be greater than zero");
        }
        this.failureThreshold = failureThreshold;
    }

    public long getResetTimeoutMs() {
        return resetTimeoutMs;
    }

    public void setResetTimeoutMs(long resetTimeoutMs) {
        if (resetTimeoutMs <= 0) {
            throw new IllegalArgumentException("circuit-breaker.reset-timeout-ms must be greater than zero");
        }
        this.resetTimeoutMs = resetTimeoutMs;
    }

    /**
     * Creates an independent copy of this circuit-breaker policy.
     *
     * @return independent circuit-breaker policy copy
     */
    public ExternalCircuitBreakerPolicy copy() {
        ExternalCircuitBreakerPolicy copy = new ExternalCircuitBreakerPolicy();
        copy.setEnabled(isEnabled);
        copy.setFailureThreshold(failureThreshold);
        copy.setResetTimeoutMs(resetTimeoutMs);
        return copy;
    }
}
