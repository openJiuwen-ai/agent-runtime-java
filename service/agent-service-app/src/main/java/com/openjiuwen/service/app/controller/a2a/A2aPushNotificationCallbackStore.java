/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

/**
 * Stores received push notification payloads for idempotency.
 *
 * @since 0.1.0
 */
public interface A2aPushNotificationCallbackStore {
    /**
     * Saves a received notification payload hash if absent.
     *
     * @param notificationId stable notification id
     * @param payloadHash canonical payload hash
     * @return save result
     */
    SaveResult saveIfAbsent(String notificationId, String payloadHash);

    /**
     * Removes an idempotency record only when its payload hash still matches.
     *
     * @param notificationId stable notification id
     * @param payloadHash canonical payload hash
     * @return true if the matching record was removed
     */
    default boolean removeIfMatch(String notificationId, String payloadHash) {
        return false;
    }

    /**
     * Result of saving an idempotency record.
     */
    enum SaveResult {
        CREATED,
        DUPLICATE,
        CONFLICT
    }
}
