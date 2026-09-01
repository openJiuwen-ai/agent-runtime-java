/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * External E2E concurrent load against a running concurrency demo service.
 *
 * <p>Requires LLM + Redis and a running service:</p>
 *
 * <pre>
 * mvn -pl agent-service-demo/example/concurrency -am spring-boot:run
 * mvn -pl agent-service-demo/example/concurrency -am test \
 *   -Ddemo.concurrency.e2e.base-url=http://127.0.0.1:8096
 * </pre>
 *
 * @since 0.1.0
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "demo.concurrency.e2e.base-url", matches = ".+")
class DeepAgentRedisConcurrentE2EIT {
    /**
     * Validates concurrent query sessions meet the configured success threshold.
     */
    @Test
    void concurrentSkillEchoSessionsMeetSuccessThreshold() {
        String baseUrl = System.getProperty("demo.concurrency.e2e.base-url");
        ConcurrentLoadConfig config = new ConcurrentLoadConfig("query", baseUrl, "http://localhost:18090", 6, 3, false,
            0, ConcurrentLoadConfig.fromEnvironment().requestTimeout(), 0.80D, 20, 2);
        ConcurrentLoadMetrics metrics = ConcurrentLoadHarness.runQueryLoad(config);
        assertThat(metrics.successRate()).isGreaterThanOrEqualTo(config.minSuccessRate());
        assertThat(metrics.failureCount()).isLessThanOrEqualTo(2);
    }
}
