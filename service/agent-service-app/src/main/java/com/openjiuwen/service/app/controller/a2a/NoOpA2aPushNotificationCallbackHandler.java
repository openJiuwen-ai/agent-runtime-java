/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

/**
 * Fallback callback handler used when no orchestrator supports callback recovery.
 *
 * @since 0.1.0
 */
public class NoOpA2aPushNotificationCallbackHandler implements A2aPushNotificationCallbackHandler {
    @Override
    public boolean onAccepted(A2aPushNotificationCallback callback) {
        return false;
    }
}
