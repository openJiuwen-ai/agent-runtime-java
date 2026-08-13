/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * External E2E concurrent load against running A2A Agent A.
 *
 * <p>Start four A2A agents first (see example/a2a/README.md), optionally with Redis overlay:</p>
 *
 * <pre>
 * mvn -pl agent-service-demo/example/concurrency -am test \
 *   -Ddemo.concurrency.e2e.a2a-base-url=http://127.0.0.1:18090
 * </pre>
 *
 * @since 0.1.0
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "demo.concurrency.e2e.a2a-base-url", matches = ".+")
class A2aConcurrentE2EIT {
    /**
     * Validates concurrent A2A sessions meet the configured success threshold.
     */
    @Test
    void concurrentA2aSessionsMeetSuccessThreshold() {
        String a2aBaseUrl = System.getProperty("demo.concurrency.e2e.a2a-base-url");
        ConcurrentLoadConfig config = new ConcurrentLoadConfig("a2a", "http://localhost:8096", a2aBaseUrl, 4, 2, false,
            0, ConcurrentLoadConfig.fromEnvironment().requestTimeout(), 0.75D, 20, 1);
        ConcurrentLoadMetrics metrics = ConcurrentLoadHarness.runA2aLoad(config);
        assertThat(metrics.successRate()).isGreaterThanOrEqualTo(config.minSuccessRate());
    }
}
