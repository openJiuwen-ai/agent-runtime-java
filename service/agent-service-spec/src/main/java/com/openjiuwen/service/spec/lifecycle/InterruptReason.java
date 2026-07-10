/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Reason for interrupting an active conversation execution.
 *
 * @since 0.1.0
 */
public enum InterruptReason {
    /** User explicitly requested interruption. */
    USER_REQUEST,
    /** Service shutdown triggered interruption. */
    LIFECYCLE_SHUTDOWN,
    /** Lifecycle manager triggered interruption. */
    LIFECYCLE_INTERRUPT,
    /** Other or unspecified interrupt reason. */
    OTHER
}
