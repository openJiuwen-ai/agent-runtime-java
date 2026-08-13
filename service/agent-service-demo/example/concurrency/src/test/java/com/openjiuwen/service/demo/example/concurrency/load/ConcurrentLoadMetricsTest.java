/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for load metrics aggregation.
 *
 * @since 0.1.0
 */
class ConcurrentLoadMetricsTest {
    @Test
    void computesSuccessRateAndPercentiles() {
        ConcurrentLoadMetrics metrics = new ConcurrentLoadMetrics();
        metrics.markStarted();
        metrics.recordSuccess(100);
        metrics.recordSuccess(200);
        metrics.recordFailure(300);
        metrics.markFinished();

        assertThat(metrics.total()).isEqualTo(3);
        assertThat(metrics.successCount()).isEqualTo(2);
        assertThat(metrics.failureCount()).isEqualTo(1);
        assertThat(metrics.successRate()).isEqualTo(2.0D / 3.0D);
        assertThat(metrics.percentileMs(0.50D)).isEqualTo(200L);
        assertThat(metrics.summary()).contains("successRate=66.67%");
    }
}
