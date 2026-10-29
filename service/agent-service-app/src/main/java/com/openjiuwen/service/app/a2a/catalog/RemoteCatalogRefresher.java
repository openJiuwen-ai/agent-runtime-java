/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TTL-driven refresher that pulls unified catalog snapshots from a
 * {@link RemoteCatalogSource} into the {@link A2ARemoteAgentCardRegistry}.
 *
 * <p>Startup semantics: the first pull runs synchronously on application
 * readiness but is never a readiness gate — a failed first pull only logs and
 * leaves the catalog empty. Steady state is a fixed-delay loop at the
 * configured TTL. Each pull applies the source snapshot through
 * {@link A2ARemoteAgentCardRegistry#replaceAll}, which is idempotent on
 * unchanged source versions. Pull faults — the {@link RemoteCatalogLoadException}
 * degradation signal and unexpected runtime faults alike — keep the previous
 * catalog, are counted consecutively, and reset the counter on recovery: the
 * pull boundary runs as a scheduled callback, so an escaping exception would
 * silently terminate the TTL loop. The source is
 * resolved from the {@link ObjectProvider} at start time, so the refresher
 * stays idle (and static discovery stays in charge) when no source bean is
 * defined — immune to auto-configuration ordering.</p>
 *
 * @since 0.1.4
 */
public class RemoteCatalogRefresher {
    private static final Logger log = LoggerFactory.getLogger(RemoteCatalogRefresher.class);

    private static final long MIN_REFRESH_TTL_MS = 1000L;

    private static final long MAX_REFRESH_TTL_MS = 30000L;

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final ObjectProvider<RemoteCatalogSource> sourceProvider;

    private final A2ARemoteAgentCardRegistry registry;

    private final long refreshTtlMs;

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicBoolean stopped = new AtomicBoolean();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private ScheduledExecutorService refreshExecutor;

    private RemoteCatalogSource source;

    /**
     * Constructs the refresher.
     *
     * @param sourceProvider the provider resolving the remote catalog source at
     *        start time
     * @param registry the remote agent card registry to apply snapshots to
     * @param properties the refresh configuration
     */
    public RemoteCatalogRefresher(ObjectProvider<RemoteCatalogSource> sourceProvider,
            A2ARemoteAgentCardRegistry registry, RemoteCatalogProperties properties) {
        this.sourceProvider = Objects.requireNonNull(sourceProvider, "sourceProvider must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        long ttl = properties.getRefreshTtlMs();
        if (ttl < MIN_REFRESH_TTL_MS || ttl > MAX_REFRESH_TTL_MS) {
            throw new IllegalArgumentException("Invalid openjiuwen.service.a2a.remote-catalog.refresh-ttl-ms " + ttl
                    + ": must be within [" + MIN_REFRESH_TTL_MS + ", " + MAX_REFRESH_TTL_MS + "]");
        }
        this.refreshTtlMs = ttl;
    }

    /**
     * Performs the first pull synchronously and starts the TTL refresh loop.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        source = sourceProvider.getIfAvailable();
        if (source == null) {
            log.info("No RemoteCatalogSource bean present; remote catalog refresher stays idle");
            return;
        }
        refreshOnce("initial");
        if (stopped.get()) {
            return;
        }
        ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = defaultThreadFactory.newThread(runnable);
            thread.setName("a2a-catalog-refresh");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (t, ex) -> log.error("Uncaught exception in catalog refresh thread {}", t.getName(), ex));
            return thread;
        });
        refreshExecutor = executor;
        executor.scheduleWithFixedDelay(this::refreshOnSchedule, refreshTtlMs, refreshTtlMs, TimeUnit.MILLISECONDS);
        log.info("Remote catalog refresher started ttlMs={} source={}", refreshTtlMs, source.getClass().getName());
    }

    /**
     * Stops the refresh loop, waiting for an in-flight pull to finish.
     */
    @PreDestroy
    public void shutdown() {
        stopped.set(true);
        ScheduledExecutorService executor = refreshExecutor;
        if (executor == null) {
            return;
        }
        log.info("Shutting down remote catalog refresher");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private void refreshOnSchedule() {
        refreshOnce("periodic");
    }

    private void refreshOnce(String phase) {
        try {
            RemoteCatalogSnapshot snapshot = source.load();
            registry.replaceAll(snapshot);
        } catch (RuntimeException e) {
            // Guard boundary (deliberate G.ERR.02 exemption): this method runs as
            // a scheduleWithFixedDelay callback — any escaping RuntimeException
            // would silently terminate the TTL loop forever, so every pull-side
            // fault (the degradation signal and unexpected implementation faults
            // alike) is converted into the degradation path. Errors still escape.
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("Remote catalog {} pull failed failures={} ttlMs={}, keeping previous catalog", phase, failures,
                    refreshTtlMs, e);
            return;
        }
        int recovered = consecutiveFailures.getAndSet(0);
        if (recovered > 0) {
            log.info("Remote catalog {} pull recovered after {} failure(s)", phase, recovered);
        }
    }
}
