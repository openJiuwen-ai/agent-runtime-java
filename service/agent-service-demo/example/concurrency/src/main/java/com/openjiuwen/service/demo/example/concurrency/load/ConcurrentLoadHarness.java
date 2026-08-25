/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
    private static final Logger log = LoggerFactory.getLogger(ConcurrentLoadHarness.class);

    private ConcurrentLoadHarness() {
    }

    /**
     * Runs multi-session concurrent load against {@code /v1/query}.
     *
     * @param config load configuration
     * @return collected metrics for the run
     */
    public static ConcurrentLoadMetrics runQueryLoad(ConcurrentLoadConfig config) {
        QueryConcurrentClient probe = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
        if (!probe.healthReady()) {
            throw new IllegalStateException("Query service not ready at " + config.baseUrl() + " (/health failed)");
        }
        runWarmup(config, index -> {
            QueryConcurrentClient warmupClient = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
            String conversationId = "warmup-" + index;
            QueryConcurrentClient.QueryResult result = warmupClient.skillEcho(conversationId, "warmup-" + index,
                config.isStream());
            if (!result.isSuccess()) {
                log.warn("[warmup] {}", result.error());
            }
        });
        ConcurrentLoadMetrics metrics = new ConcurrentLoadMetrics();
        metrics.markStarted();
        runSessions(config, index -> {
            QueryConcurrentClient client = new QueryConcurrentClient(config.baseUrl(), config.requestTimeout());
            String conversationId = "bench-q-" + index;
            int rounds = Math.max(1, config.roundsPerSession());
            String lastError = null;
            for (int round = 0; round < rounds; round++) {
                QueryConcurrentClient.QueryResult result;
                if (round % 2 == 0) {
                    result = client.skillEcho(conversationId, "token-" + index + "-r" + round, config.isStream());
                } else {
                    result = client.lookup(conversationId, "key-" + index + "-r" + round, config.lookupDelayMs(),
                        config.isStream());
                }
                record(metrics, result.isSuccess(), result.latencyMs());
                if (!result.isSuccess()) {
                    lastError = result.error();
                    break;
                }
            }
            return lastError;
        });
        metrics.markFinished();
        return metrics;
    }

    /**
     * Runs multi-session concurrent load against an A2A endpoint.
     *
     * @param config load configuration
     * @return collected metrics for the run
     */
    public static ConcurrentLoadMetrics runA2aLoad(ConcurrentLoadConfig config) {
        A2aConcurrentClient probe = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
        if (!probe.healthReady()) {
            throw new IllegalStateException(
                "A2A service not ready at " + config.a2aBaseUrl() + " (/health failed). Start a2a agents first.");
        }
        runWarmup(config, index -> {
            A2aConcurrentClient warmupClient = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
            A2aConcurrentClient.A2aResult result = config.isStream()
                ? warmupClient.sendStreamingMessage("a2a-warmup-" + index, "hello from warmup " + index)
                : warmupClient.sendMessage("a2a-warmup-" + index, "hello from warmup " + index);
            if (!result.isSuccess()) {
                log.warn("[a2a-warmup] {}", result.error());
            }
        });
        ConcurrentLoadMetrics metrics = new ConcurrentLoadMetrics();
        metrics.markStarted();
        runSessions(config, index -> {
            A2aConcurrentClient client = new A2aConcurrentClient(config.a2aBaseUrl(), config.requestTimeout());
            String contextId = "a2a-bench-" + index;
            String message = "Please calculate 1+1 using calc tool.";
            A2aConcurrentClient.A2aResult result = config.isStream()
                ? client.sendStreamingMessage(contextId, message)
                : client.sendMessage(contextId, message);
            record(metrics, result.isSuccess(), result.latencyMs());
            return result.isSuccess() ? null : result.error();
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
        ExecutorService executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());
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
                log.warn("Sample errors ({} shown):", Math.min(3, errors.size()));
                errors.stream().limit(3).forEach(error -> log.warn("  - {}", error));
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

    private static void record(ConcurrentLoadMetrics metrics, boolean isSuccess, long latencyMs) {
        if (isSuccess) {
            metrics.recordSuccess(latencyMs);
        } else {
            metrics.recordFailure(latencyMs);
        }
    }
}
