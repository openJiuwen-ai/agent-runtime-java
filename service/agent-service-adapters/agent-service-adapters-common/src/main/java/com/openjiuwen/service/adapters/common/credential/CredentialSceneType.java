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

    /** Long-term memory provider API key credential. */
    public static final int MEMORY_API_KEY = 3;

    /** MCP outbound auth token credential. */
    public static final int MCP_AUTH_TOKEN = 10;

    /** Remote(A2A) outbound auth token credential. */
    public static final int REMOTE_AUTH_TOKEN = 11;

    /** Sandbox outbound auth token credential. */
    public static final int SANDBOX_AUTH_TOKEN = 12;

    /** Ingress TLS key store password credential. */
    public static final int TLS_KEYSTORE_PASSWORD = 13;

    /** Ingress/outbound TLS trust store password credential. */
    public static final int TLS_TRUSTSTORE_PASSWORD = 14;

    private CredentialSceneType() {
    }
}
