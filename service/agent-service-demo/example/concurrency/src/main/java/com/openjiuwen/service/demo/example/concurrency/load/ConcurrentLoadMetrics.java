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

    /** Marks the start timestamp for duration and QPS calculation. */
    public void markStarted() {
        startedAtNanos.compareAndSet(0L, System.nanoTime());
    }

    /** Marks the finish timestamp for duration and QPS calculation. */
    public void markFinished() {
        finishedAtNanos.set(System.nanoTime());
    }

    /**
     * Records a successful request latency sample.
     *
     * @param latencyMs observed latency in milliseconds
     */
    public void recordSuccess(long latencyMs) {
        successCount.incrementAndGet();
        latenciesMs.add(latencyMs);
    }

    /**
     * Records a failed request latency sample.
     *
     * @param latencyMs observed latency in milliseconds
     */
    public void recordFailure(long latencyMs) {
        failureCount.incrementAndGet();
        latenciesMs.add(latencyMs);
    }

    /**
     * Returns total completed requests (success + failure).
     *
     * @return total request count
     */
    public int total() {
        return successCount.get() + failureCount.get();
    }

    /**
     * Returns the number of successful requests.
     *
     * @return success count
     */
    public int successCount() {
        return successCount.get();
    }

    /**
     * Returns the number of failed requests.
     *
     * @return failure count
     */
    public int failureCount() {
        return failureCount.get();
    }

    /**
     * Returns the success ratio in {@code [0.0, 1.0]}.
     *
     * @return success rate
     */
    public double successRate() {
        int total = total();
        return total == 0 ? 0.0D : (double) successCount.get() / total;
    }

    /**
     * Returns elapsed wall time between {@link #markStarted()} and {@link #markFinished()}.
     *
     * @return duration in seconds
     */
    public double durationSeconds() {
        long start = startedAtNanos.get();
        long end = finishedAtNanos.get();
        if (start == 0L || end == 0L || end <= start) {
            return 0.0D;
        }
        return (end - start) / 1_000_000_000.0D;
    }

    /**
     * Returns requests per second over the measured duration.
     *
     * @return queries per second
     */
    public double qps() {
        double duration = durationSeconds();
        return duration <= 0.0D ? 0.0D : total() / duration;
    }

    /**
     * Returns the latency percentile from collected samples.
     *
     * @param percentile value in {@code (0.0, 1.0]}, e.g. {@code 0.95} for p95
     * @return latency in milliseconds
     */
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

    /**
     * Returns a single-line human-readable summary for logging.
     *
     * @return formatted metrics summary
     */
    public String summary() {
        return String.format(Locale.ROOT,
            "total=%d success=%d failure=%d successRate=%.2f%% duration=%.2fs qps=%.2f p50=%dms p95=%dms p99=%dms",
            total(), successCount.get(), failureCount.get(), successRate() * 100.0D, durationSeconds(), qps(),
            percentileMs(0.50D), percentileMs(0.95D), percentileMs(0.99D));
    }
}
