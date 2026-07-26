/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory push notification callback idempotency store.
 *
 * @since 0.1.0
 */
public class InMemoryA2aPushNotificationCallbackStore implements A2aPushNotificationCallbackStore {
    private final ConcurrentMap<String, String> payloadHashes = new ConcurrentHashMap<>();

    @Override
    public SaveResult saveIfAbsent(String notificationId, String payloadHash) {
        String existing = payloadHashes.putIfAbsent(notificationId, payloadHash);
        if (existing == null) {
            return SaveResult.CREATED;
        }
        return existing.equals(payloadHash) ? SaveResult.DUPLICATE : SaveResult.CONFLICT;
    }
}
