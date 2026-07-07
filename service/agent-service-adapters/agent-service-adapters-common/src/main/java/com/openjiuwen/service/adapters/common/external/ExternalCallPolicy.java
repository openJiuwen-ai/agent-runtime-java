/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * External service call policy used by adapter decorators.
 *
 * @since 2026-06-24
 */
public interface ExternalCallPolicy {
    /**
     * Returns the external call timeout in milliseconds.
     *
     * @return external call timeout in milliseconds
     */
    int getTimeoutMs();

    /**
     * Returns the retry policy for external calls.
     *
     * @return retry policy for external calls
     */
    ExternalRetryPolicy getRetry();

    /**
     * Returns the circuit-breaker policy for external calls.
     *
     * @return circuit-breaker policy for external calls
     */
    ExternalCircuitBreakerPolicy getCircuitBreaker();

    /**
     * Returns the audit policy for external calls.
     *
     * @return audit policy for external calls
     */
    ExternalAuditPolicy getAudit();
}
