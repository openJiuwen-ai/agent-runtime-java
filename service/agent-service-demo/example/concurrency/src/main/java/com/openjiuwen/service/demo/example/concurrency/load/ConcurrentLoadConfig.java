/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import java.time.Duration;
import java.util.Locale;

/**
 * Load test configuration from system properties and environment variables.
 *
 * @since 0.1.0
 */
public record ConcurrentLoadConfig(String mode, String baseUrl, String a2aBaseUrl, int sessions, int concurrency,
    boolean stream, int warmupSessions, Duration requestTimeout, double minSuccessRate, int lookupDelayMs,
    int roundsPerSession) {

    public static final String PROP_PREFIX = "demo.concurrency.";

    public static ConcurrentLoadConfig fromEnvironment() {
        return new ConcurrentLoadConfig(string("mode", "query"), string("base-url", "http://localhost:8096"),
            string("a2a-base-url", "http://localhost:18090"), integer("sessions", 20), integer("concurrency", 10),
            bool("stream", false), integer("warmup", 0), Duration.ofSeconds(integer("timeout-seconds", 120)),
            doubleValue("min-success-rate", 0.95D), integer("lookup-delay-ms", 50), integer("rounds-per-session", 2));
    }

    public boolean isQueryMode() {
        return "query".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode);
    }

    public boolean isA2aMode() {
        return "a2a".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode);
    }

    private static String string(String key, String defaultValue) {
        return firstNonBlank(System.getProperty(PROP_PREFIX + key), System.getenv(envKey(key)), defaultValue);
    }

    private static int integer(String key, int defaultValue) {
        String raw = string(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double doubleValue(String key, double defaultValue) {
        String raw = string(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean bool(String key, boolean defaultValue) {
        String raw = string(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(raw.trim());
    }

    private static String envKey(String key) {
        return ("DEMO_CONCURRENCY_" + key.replace('-', '_')).toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @Override
    public String toString() {
        return "ConcurrentLoadConfig{mode=%s, baseUrl=%s, a2aBaseUrl=%s, sessions=%d, concurrency=%d, stream=%s, warmup=%d, timeout=%s, minSuccessRate=%.2f, lookupDelayMs=%d, roundsPerSession=%d}"
            .formatted(mode, baseUrl, a2aBaseUrl, sessions, concurrency, stream, warmupSessions, requestTimeout,
                minSuccessRate, lookupDelayMs, roundsPerSession);
    }
}
