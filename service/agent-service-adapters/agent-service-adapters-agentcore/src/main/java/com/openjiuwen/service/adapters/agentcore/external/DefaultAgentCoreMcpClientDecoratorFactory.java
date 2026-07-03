/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;

/**
 * Default factory for decorating Core MCP clients with Service policies.
 *
 * @since 2026-06-24
 */
public class DefaultAgentCoreMcpClientDecoratorFactory implements AgentCoreMcpClientDecoratorFactory {
    @Override
    public McpClient decorate(McpServerConfig config, McpClient delegate,
            AgentCoreExternalProperties.McpPolicy policy) {
        return new DecoratingMcpClient(config, delegate, policy);
    }
}
