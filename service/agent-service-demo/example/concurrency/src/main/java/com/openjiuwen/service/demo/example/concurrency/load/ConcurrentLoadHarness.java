/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Orchestrates multi-session concurrent load against Query and A2A endpoints.
 *
 * @since 0.1.0
 */
public final class ConcurrentLoadHarness {
    private ConcurrentLoadHarness() {
    }

    public static ConcurrentLoadMetrics runQueryLoad(ConcurrentLoadConfig config) {
        QueryConcurrentClient probe = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
        if (!probe.healthReady()) {
            throw new IllegalStateException("Query service not ready at " + config.baseUrl() + " (/health failed)");
        }
        runWarmup(config, index -> {
            QueryConcurrentClient warmupClient = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
            String conversationId = "warmup-" + index;
            QueryConcurrentClient.QueryResult result = warmupClient.skillEcho(conversationId, "warmup-" + index,
                config.stream());
            if (!result.success()) {
                System.err.println("[warmup] " + result.error());
            }
        });
        ConcurrentLoadMetrics metrics = new ConcurrentLoadMetrics();
        metrics.markStarted();
        runSessions(config, index -> {
            // One HttpClient per worker thread — shared clients serialize long-lived SSE connections.
            QueryConcurrentClient client = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
            String conversationId = "bench-q-" + index;
            int rounds = Math.max(1, config.roundsPerSession());
            String lastError = null;
            for (int round = 0; round < rounds; round++) {
                QueryConcurrentClient.QueryResult result;
                if (round % 2 == 0) {
                    result = client.skillEcho(conversationId, "token-" + index + "-r" + round, config.stream());
                } else {
                    result = client.lookup(conversationId, "key-" + index + "-r" + round, config.lookupDelayMs(),
                        config.stream());
                }
                record(metrics, result.success(), result.latencyMs());
                if (!result.success()) {
                    lastError = result.error();
                    break;
                }
            }
            return lastError;
        });
        metrics.markFinished();
        return metrics;
    }

    public static ConcurrentLoadMetrics runA2aLoad(ConcurrentLoadConfig config) {
        A2aConcurrentClient probe = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
        if (!probe.healthReady()) {
            throw new IllegalStateException(
                "A2A service not ready at " + config.a2aBaseUrl() + " (/health failed). Start a2a agents first.");
        }
        runWarmup(config, index -> {
            A2aConcurrentClient warmupClient = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
            A2aConcurrentClient.A2aResult result = config.stream()
                ? warmupClient.sendStreamingMessage("a2a-warmup-" + index, "hello from warmup " + index)
                : warmupClient.sendMessage("a2a-warmup-" + index, "hello from warmup " + index);
            if (!result.success()) {
                System.err.println("[a2a-warmup] " + result.error());
            }
        });
        ConcurrentLoadMetrics metrics = new ConcurrentLoadMetrics();
        metrics.markStarted();
        runSessions(config, index -> {
            A2aConcurrentClient client = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
            String contextId = "a2a-bench-" + index;
            String message = "Please calculate 1+1 using calc tool.";
            A2aConcurrentClient.A2aResult result = config.stream()
                ? client.sendStreamingMessage(contextId, message)
                : client.sendMessage(contextId, message);
            record(metrics, result.success(), result.latencyMs());
            return result.success() ? null : result.error();
        });
        metrics.markFinished();
        return metrics;
    }

    private static void runWarmup(ConcurrentLoadConfig config, IntConsumer action) {
        if (config.warmupSessions() <= 0) {
            return;
        }
        for (int index = 0; index < config.warmupSessions(); index++) {
            action.accept(index);
        }
    }

    private static void runSessions(ConcurrentLoadConfig config, IntFunction<String> action) {
        int concurrency = Math.max(1, config.concurrency());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            AtomicInteger sessionIndex = new AtomicInteger();
            for (int slot = 0; slot < config.sessions(); slot++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    int index = sessionIndex.getAndIncrement();
                    return action.apply(index);
                }, executor));
            }
            List<String> errors = futures.stream().map(CompletableFuture::join).filter(error -> error != null).toList();
            if (!errors.isEmpty()) {
                System.err.println("Sample errors (" + Math.min(3, errors.size()) + " shown):");
                errors.stream().limit(3).forEach(error -> System.err.println("  - " + error));
            }
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(config.requestTimeout().toSeconds() * config.sessions(), TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void record(ConcurrentLoadMetrics metrics, boolean success, long latencyMs) {
        if (success) {
            metrics.recordSuccess(latencyMs);
        } else {
            metrics.recordFailure(latencyMs);
        }
    }
}
