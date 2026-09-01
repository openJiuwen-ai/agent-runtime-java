/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

/**
 * Handles first-seen A2A push notification callbacks after receiver validation and idempotency checks.
 *
 * @since 0.1.0
 */
public interface A2aPushNotificationCallbackHandler {
    /**
     * Handles an accepted callback.
     *
     * @param callback normalized callback payload
     * @return true if the callback was bound to local recoverable state
     */
    boolean onAccepted(A2aPushNotificationCallback callback);
}
