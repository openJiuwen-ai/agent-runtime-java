/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import org.a2aproject.sdk.spec.AgentCard;

/**
 * Immutable remote A2A Agent registration entry.
 *
 * @param name remote Agent name
 * @param card discovered Agent Card
 * @param timeoutSeconds remote call timeout in seconds
 * @param isStreaming whether Runtime should prefer streaming invocation
 * @since 0.1.1
 */
public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {
}
