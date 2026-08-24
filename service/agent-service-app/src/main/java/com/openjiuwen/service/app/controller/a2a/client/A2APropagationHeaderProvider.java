/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.Map;

/**
 * Provides propagation headers for one outbound A2A request. Registered through
 * {@link A2APropagationHeaderRegistry#registerProvider(A2APropagationHeaderProvider)}
 * and invoked by {@link HeaderInjectingA2AHttpClient} at request-execution time; see
 * the registry's class javadoc for the full provider contract (threading, header
 * conflict and exception rules).
 *
 * @since 0.1.2
 */
@FunctionalInterface
public interface A2APropagationHeaderProvider {
    /**
     * Computes the headers to inject for one request.
     *
     * @param request the outbound request coordinates
     * @return headers to inject (empty when there is nothing to propagate)
     */
    Map<String, String> headersFor(A2AOutboundRequest request);
}
