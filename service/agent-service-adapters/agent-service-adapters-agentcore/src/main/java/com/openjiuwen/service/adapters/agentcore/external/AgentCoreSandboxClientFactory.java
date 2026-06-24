/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;

/**
 * Factory for creating agent-core sandbox clients from external sandbox configuration.
 *
 * @since 2026-06-24
 */
public interface AgentCoreSandboxClientFactory {
    /**
     * Creates a sandbox client from the default configured sandbox server.
     *
     * @return sandbox client for the default configured server
     */
    SandboxClient create();

    /**
     * Creates a sandbox client from the configured sandbox server id.
     *
     * @param serverId sandbox server id
     * @return sandbox client for the configured server
     */
    SandboxClient create(String serverId);

    /**
     * Returns the sandbox gateway configuration for the configured sandbox server id.
     *
     * @param serverId sandbox server id
     * @return sandbox gateway configuration for the configured server
     */
    SandboxGatewayConfig configFor(String serverId);
}
