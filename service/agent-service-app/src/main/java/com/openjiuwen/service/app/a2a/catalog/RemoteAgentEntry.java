/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;

import org.a2aproject.sdk.spec.AgentCard;

/**
 * Immutable remote A2A Agent registration entry.
 *
 * @param name remote Agent name
 * @param card discovered Agent Card
 * @param timeoutSeconds remote call timeout in seconds
 * @param isStreaming whether Runtime should prefer streaming invocation
 * @param tls target-specific outbound TLS configuration, or null for defaults
 * @since 0.1.1
 */
public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming,
        ExternalTlsConfig tls) {

    /**
     * Creates an entry without target-specific TLS settings.
     *
     * @param name remote agent name
     * @param card discovered agent card
     * @param timeoutSeconds remote call timeout in seconds
     * @param isStreaming whether Runtime should prefer streaming invocation
     */
    public RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {
        this(name, card, timeoutSeconds, isStreaming, null);
    }
}
