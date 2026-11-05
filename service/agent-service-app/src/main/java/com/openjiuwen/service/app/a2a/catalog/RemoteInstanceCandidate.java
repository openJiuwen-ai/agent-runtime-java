/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

/**
 * One routable instance candidate: the resolved physical JSON-RPC endpoint plus
 * its routing weight.
 *
 * <p>The URL is already resolved (for dynamic candidates, via the route-handle
 * resolution chain in the extension source); it is the only address the caller
 * uses. A weight of zero or below is treated as equal weight by the selector,
 * which degrades weighted choice to uniform random.</p>
 *
 * @param instanceId registry-side instance identifier, empty for the static
 *        single-candidate compatibility view
 * @param url the resolved physical JSON-RPC endpoint of the instance
 * @param weight routing weight; zero or below means equal weight
 * @since 0.1.4
 */
public record RemoteInstanceCandidate(String instanceId, String url, int weight) {
}
