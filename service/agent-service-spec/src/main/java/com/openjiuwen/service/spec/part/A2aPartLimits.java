/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.part;

/**
 * Default A2A part protocol limits (design doc FEAT-036 §4.1, values from §2.2).
 * Callers may override them via configuration; these are the spec defaults.
 *
 * @since 0.1.0
 */
public final class A2aPartLimits {
    /** Maximum decoded size of a single raw part: 10MB. */
    public static final long DEFAULT_MAX_RAW_BYTES = 10L * 1024 * 1024;

    /** Maximum number of parts in one message: 100. */
    public static final int DEFAULT_MAX_PARTS = 100;

    /** Maximum size of a single inbound HTTP body: 100MB. */
    public static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 100L * 1024 * 1024;

    /** Maximum serialized size of a single text or data part: 1MB. */
    public static final long DEFAULT_MAX_TEXT_DATA_BYTES = 1024L * 1024;

    private A2aPartLimits() {
    }
}
