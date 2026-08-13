/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry for DeepAgent + Redis + A2A concurrent load validation.
 *
 * <p>Example:</p>
 *
 * <pre>
 * mvn -pl agent-service-demo/example/concurrency -am exec:java \
 *   -Ddemo.concurrency.mode=query \
 *   -Ddemo.concurrency.sessions=40 \
 *   -Ddemo.concurrency.concurrency=20
 * </pre>
 *
 * @since 0.1.0
 */
public final class ConcurrentLoadRunner {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentLoadRunner.class);

    private ConcurrentLoadRunner() {
    }

    /**
     * Runs configured query and/or A2A load scenarios and exits with a process code.
     *
     * @param args command-line arguments (unused; config comes from system properties)
     */
    public static void main(String[] args) {
        ConcurrentLoadConfig config = ConcurrentLoadConfig.fromEnvironment();
        log.info("Concurrent load config: {}", config);
        int exitCode = 0;
        if (config.isQueryMode()) {
            exitCode = Math.max(exitCode, runScenario("query", ConcurrentLoadHarness.runQueryLoad(config), config));
        }
        if (config.isA2aMode()) {
            exitCode = Math.max(exitCode, runScenario("a2a", ConcurrentLoadHarness.runA2aLoad(config), config));
        }
        if (!config.isQueryMode() && !config.isA2aMode()) {
            log.error("Unknown mode: {} (expected query, a2a, or both)", config.mode());
            System.exit(2);
        }
        System.exit(exitCode);
    }

    /**
     * Logs scenario metrics and compares success rate to the configured threshold.
     *
     * @param label scenario label for logging
     * @param metrics collected load metrics
     * @param config load configuration containing the threshold
     * @return {@code 0} when the threshold is met, otherwise {@code 1}
     */
    private static int runScenario(String label, ConcurrentLoadMetrics metrics, ConcurrentLoadConfig config) {
        log.info("[{}] {}", label, metrics.summary());
        if (metrics.successRate() < config.minSuccessRate()) {
            log.error("[{}] FAIL successRate {}% below threshold {}%", label,
                metrics.successRate() * 100.0D, config.minSuccessRate() * 100.0D);
            return 1;
        }
        log.info("[{}] PASS", label);
        return 0;
    }
}
