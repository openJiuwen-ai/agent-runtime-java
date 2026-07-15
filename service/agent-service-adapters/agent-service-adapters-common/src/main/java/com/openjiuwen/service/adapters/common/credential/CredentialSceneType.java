/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.credential;

/**
 * Stable scene identifiers passed to {@link CredentialDecryptor}.
 *
 * @since 0.1.0
 */
public final class CredentialSceneType {
    /** Scene used by legacy callers that do not distinguish credential types. */
    public static final int UNKNOWN = 0;

    /** Redis password credential. */
    public static final int REDIS_PASSWORD = 1;

    /** LLM API key credential. */
    public static final int LLM_API_KEY = 2;

    private CredentialSceneType() {
    }
}
