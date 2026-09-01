/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory push notification callback idempotency store.
 * <p>
 * Uses an LRU-bounded map to prevent unbounded memory growth in long-running
 * scenarios. The default capacity of {@value #DEFAULT_MAX_ENTRIES} entries is
 * sufficient for deduplication of recent notifications while bounding memory.
 *
 * @since 0.1.0
 */
public class InMemoryA2aPushNotificationCallbackStore implements A2aPushNotificationCallbackStore {
    private static final int DEFAULT_MAX_ENTRIES = 4096;

    private final Map<String, String> payloadHashes;

    public InMemoryA2aPushNotificationCallbackStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryA2aPushNotificationCallbackStore(int maxEntries) {
        this.payloadHashes = Collections.synchronizedMap(new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public SaveResult saveIfAbsent(String notificationId, String payloadHash) {
        synchronized (payloadHashes) {
            String existing = payloadHashes.get(notificationId);
            if (existing == null) {
                payloadHashes.put(notificationId, payloadHash);
                return SaveResult.CREATED;
            }
            return existing.equals(payloadHash) ? SaveResult.DUPLICATE : SaveResult.CONFLICT;
        }
    }

    @Override
    public boolean removeIfMatch(String notificationId, String payloadHash) {
        synchronized (payloadHashes) {
            return payloadHashes.remove(notificationId, payloadHash);
        }
    }
}
