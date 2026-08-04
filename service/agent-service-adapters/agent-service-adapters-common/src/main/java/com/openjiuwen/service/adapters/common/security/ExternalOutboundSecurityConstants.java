/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

/**
 * Reserved {@code params}/{@code kwargs} keys for outbound security injection into agent-core.
 *
 * @since 0.1.0
 */
public final class ExternalOutboundSecurityConstants {
    /** Injected JDK {@link java.net.http.HttpClient} for MCP HTTP transports. */
    public static final String PARAM_HTTP_CLIENT = "_ojw_http_client";

    /** Injected JDK {@link java.net.http.HttpClient} for remote(A2A) clients. */
    public static final String KWARG_HTTP_CLIENT = "_ojw_http_client";

    /** Injected auth headers for remote(A2A) clients. */
    public static final String KWARG_AUTH_HEADERS = "_ojw_auth_headers";

    /** Injected auth query params for remote(A2A) clients. */
    public static final String KWARG_AUTH_QUERY_PARAMS = "_ojw_auth_query_params";

    /** Injected OkHttp client for sandbox jiuwenbox providers. */
    public static final String PARAM_OKHTTP_CLIENT = "_ojw_okhttp_client";

    private ExternalOutboundSecurityConstants() {
    }
}
