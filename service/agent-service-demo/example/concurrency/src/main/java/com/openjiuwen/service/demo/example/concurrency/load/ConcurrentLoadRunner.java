/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

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
    private ConcurrentLoadRunner() {
    }

    public static void main(String[] args) {
        ConcurrentLoadConfig config = ConcurrentLoadConfig.fromEnvironment();
        System.out.println("Concurrent load config: " + config);
        int exitCode = 0;
        if (config.isQueryMode()) {
            exitCode = Math.max(exitCode, runScenario("query", ConcurrentLoadHarness.runQueryLoad(config), config));
        }
        if (config.isA2aMode()) {
            exitCode = Math.max(exitCode, runScenario("a2a", ConcurrentLoadHarness.runA2aLoad(config), config));
        }
        if (!config.isQueryMode() && !config.isA2aMode()) {
            System.err.println("Unknown mode: " + config.mode() + " (expected query, a2a, or both)");
            System.exit(2);
        }
        System.exit(exitCode);
    }

    private static int runScenario(String label, ConcurrentLoadMetrics metrics, ConcurrentLoadConfig config) {
        System.out.println("[" + label + "] " + metrics.summary());
        if (metrics.successRate() < config.minSuccessRate()) {
            System.err.printf("[%s] FAIL successRate %.2f%% below threshold %.2f%%%n", label,
                metrics.successRate() * 100.0D, config.minSuccessRate() * 100.0D);
            return 1;
        }
        System.out.println("[" + label + "] PASS");
        return 0;
    }
}
