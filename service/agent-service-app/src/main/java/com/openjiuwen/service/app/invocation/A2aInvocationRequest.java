/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One in-process A2A request. Headers are an immutable, case-insensitive
 * snapshot, not authentication. Only the existing identity allowlist is consumed.
 *
 * @param agentId hosted registration ID, or null for the default agent
 * @param jsonRpcRequest complete JSON-RPC request body
 * @param headers optional single-value request headers
 */
public record A2aInvocationRequest(String agentId, String jsonRpcRequest, Map<String, String> headers) {
    public A2aInvocationRequest {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key == null || value == null) {
                    throw new IllegalArgumentException("Header names and values must not be null");
                }
                if (snapshot.putIfAbsent(key.toLowerCase(Locale.ROOT), value) != null) {
                    throw new IllegalArgumentException("Duplicate case-insensitive header name");
                }
            });
        }
        headers = Map.copyOf(snapshot);
    }
}
