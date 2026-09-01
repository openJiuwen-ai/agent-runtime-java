/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Structured context of one outbound A2A request, handed to
 * {@link A2APropagationHeaderProvider} at request-execution time.
 *
 * <p>All coordinates describe the runtime-to-downstream (A-&gt;B) request, not the
 * inbound user-to-runtime request. {@code body} follows the A2A JSON-RPC message
 * structure and is {@code null} for GET/DELETE; providers that need the message
 * {@code contextId} may parse it from the body.
 *
 * @param url    downstream A2A endpoint URL
 * @param method HTTP method ({@code GET}, {@code POST} or {@code DELETE})
 * @param body   outbound JSON-RPC body, {@code null} for GET/DELETE
 * @since 0.1.2
 */
public record A2AOutboundRequest(String url, String method, String body) {
}
