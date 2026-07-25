/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

/**
 * Stores received push notification payloads for idempotency.
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

    enum SaveResult {
        CREATED,
        DUPLICATE,
        CONFLICT
    }
}
