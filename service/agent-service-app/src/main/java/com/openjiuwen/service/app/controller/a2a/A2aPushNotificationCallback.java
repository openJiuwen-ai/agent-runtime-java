/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.spec.Task;

/**
 * Normalized push notification callback accepted by the fixed A2A receiver.
 *
 * @param notificationId stable notification id used for callback idempotency
 * @param task remote A2A task carried by the JSON-RPC result
 */
public record A2aPushNotificationCallback(String notificationId, Task task) {
}
