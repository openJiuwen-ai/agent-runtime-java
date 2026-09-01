/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executor for pumping agent stream chunks to {@code SseEmitter}.
 *
 * <p>On JDK 21 and later, each active SSE connection runs on its own virtual thread without executor-level
 * concurrency or queue limits. On JDK 17, the existing bounded platform pool is retained: default
 * {@code max(32, CPU×8)} worker threads plus an {@link ArrayBlockingQueue} with a default capacity of 128.
 * When the JDK 17 pool is saturated, {@link #execute(Runnable)} throws {@link RejectedExecutionException}
 * so the HTTP layer can fail fast with 503.</p>
 *
 * <p>JDK 17 configuration (system property or environment variable):</p>
 * <ul>
 *   <li>{@code openjiuwen.service.query.sse-pump.max-size} / {@code OPENJIUWEN_SERVICE_QUERY_SSE_PUMP_MAX_SIZE}</li>
 *   <li>{@code openjiuwen.service.query.sse-pump.queue-size} /
 *       {@code OPENJIUWEN_SERVICE_QUERY_SSE_PUMP_QUEUE_SIZE}</li>
 * </ul>
 *
 * @since 0.1.0
 */
final class QuerySsePumpExecutor {
    private static final Logger log = LoggerFactory.getLogger(QuerySsePumpExecutor.class);

    private static final String MAX_SIZE_PROPERTY = "openjiuwen.service.query.sse-pump.max-size";

    private static final String QUEUE_SIZE_PROPERTY = "openjiuwen.service.query.sse-pump.queue-size";

    private static final int DEFAULT_MAX_SIZE = Math.max(32, Runtime.getRuntime().availableProcessors() * 8);

    private static final int DEFAULT_QUEUE_SIZE = 128;

    private static final long KEEP_ALIVE_SECONDS = 60L;

    private static final ExecutorService EXECUTOR = createExecutor();

    private QuerySsePumpExecutor() {
    }

    /**
     * Returns the shared SSE pump executor.
     *
     * @return singleton executor service
     */
    static ExecutorService executor() {
        return EXECUTOR;
    }

    /**
     * Submits an SSE pump task.
     *
     * @param task pump runnable
     * @throws RejectedExecutionException when the JDK 17 pool is saturated or the executor is shut down
     */
    static void execute(Runnable task) {
        EXECUTOR.execute(task);
    }

    /**
     * Creates a bounded pump pool for tests or custom wiring.
     *
     * @param maxSize maximum worker threads
     * @return configured thread pool executor
     */
    static ThreadPoolExecutor newPool(int maxSize) {
        return newPool(maxSize, DEFAULT_QUEUE_SIZE);
    }

    /**
     * Creates a bounded pump pool for tests or custom wiring.
     *
     * @param maxSize maximum worker threads (also core size; threads stay hot for long-lived pump tasks)
     * @param queueSize bounded overflow queue capacity
     * @return configured thread pool executor
     */
    static ThreadPoolExecutor newPool(int maxSize, int queueSize) {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(maxSize, maxSize, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    Thread thread = new Thread(runnable, "query-sse-pump-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((source, error) -> log
                            .error("Uncaught query SSE pump error thread={}", source.getName(), error));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        log.info("Query SSE pump executor: maxSize={}, queueSize={}, core=maxSize", maxSize, queueSize);
        return executor;
    }

    private static ExecutorService createExecutor() {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("query-sse-pump",
                    (thread, error) -> log.error("Uncaught query SSE pump error thread={}",
                            thread.getName(), error));
        }
        return newPool(intSetting(MAX_SIZE_PROPERTY, DEFAULT_MAX_SIZE, 1),
                intSetting(QUEUE_SIZE_PROPERTY, DEFAULT_QUEUE_SIZE, 1));
    }

    private static int intSetting(String propertyKey, int defaultValue, int minimum) {
        String raw = System.getProperty(propertyKey);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(toEnvKey(propertyKey));
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(minimum, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            log.warn("Invalid integer for {}={}, using default {}", propertyKey, raw, defaultValue);
            return defaultValue;
        }
    }

    private static String toEnvKey(String propertyKey) {
        return propertyKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
