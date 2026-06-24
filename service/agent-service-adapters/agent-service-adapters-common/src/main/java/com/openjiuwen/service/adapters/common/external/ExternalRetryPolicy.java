/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * Retry policy for external service calls.
 *
 * @since 2026-06-24
 */
public class ExternalRetryPolicy {
    private int max = 0;

    private long backoffMs = 0;

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = Math.max(0, max);
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = Math.max(0, backoffMs);
    }

    /**
     * Creates an independent copy of this retry policy.
     *
     * @return independent retry policy copy
     */
    public ExternalRetryPolicy copy() {
        ExternalRetryPolicy copy = new ExternalRetryPolicy();
        copy.setMax(max);
        copy.setBackoffMs(backoffMs);
        return copy;
    }
}
