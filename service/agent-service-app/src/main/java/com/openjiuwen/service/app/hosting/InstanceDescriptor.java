/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import org.a2aproject.sdk.spec.AgentCard;

/**
 * One registrable instance of this process: the identity, the JSON-RPC
 * endpoint path it serves, and the agent card fact to register. The
 * registration side (extension module) derives the full physical address by
 * joining the process address with the endpoint path.
 *
 * @param agentId the local agent identifier
 * @param endpointPath the JSON-RPC endpoint path the instance serves
 * @param card the agent card fact of the instance
 * @since 0.1.4
 */
public record InstanceDescriptor(String agentId, String endpointPath, AgentCard card) {
}
