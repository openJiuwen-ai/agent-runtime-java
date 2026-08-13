/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe latency and success metrics for concurrent load runs.
 *
 * @since 0.1.0
 */
public final class ConcurrentLoadMetrics {
    private final AtomicInteger successCount = new AtomicInteger();

    private final AtomicInteger failureCount = new AtomicInteger();

    private final List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong startedAtNanos = new AtomicLong();

    private final AtomicLong finishedAtNanos = new AtomicLong();

    public void markStarted() {
        startedAtNanos.compareAndSet(0L, System.nanoTime());
    }

    public void markFinished() {
        finishedAtNanos.set(System.nanoTime());
    }

    public void recordSuccess(long latencyMs) {
        successCount.incrementAndGet();
        latenciesMs.add(latencyMs);
    }

    public void recordFailure(long latencyMs) {
        failureCount.incrementAndGet();
        latenciesMs.add(latencyMs);
    }

    public int total() {
        return successCount.get() + failureCount.get();
    }

    public int successCount() {
        return successCount.get();
    }

    public int failureCount() {
        return failureCount.get();
    }

    public double successRate() {
        int total = total();
        return total == 0 ? 0.0D : (double) successCount.get() / total;
    }

    public double durationSeconds() {
        long start = startedAtNanos.get();
        long end = finishedAtNanos.get();
        if (start == 0L || end == 0L || end <= start) {
            return 0.0D;
        }
        return (end - start) / 1_000_000_000.0D;
    }

    public double qps() {
        double duration = durationSeconds();
        return duration <= 0.0D ? 0.0D : total() / duration;
    }

    public long percentileMs(double percentile) {
        if (latenciesMs.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(latenciesMs);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    public String summary() {
        return String.format(Locale.ROOT,
            "total=%d success=%d failure=%d successRate=%.2f%% duration=%.2fs qps=%.2f p50=%dms p95=%dms p99=%dms",
            total(), successCount.get(), failureCount.get(), successRate() * 100.0D, durationSeconds(), qps(),
            percentileMs(0.50D), percentileMs(0.95D), percentileMs(0.99D));
    }
}
