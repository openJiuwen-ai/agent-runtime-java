/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Reason for interrupting an active conversation execution.
 */
public enum InterruptReason {
    USER_REQUEST,
    LIFECYCLE_SHUTDOWN,
    LIFECYCLE_INTERRUPT,
    OTHER
}
