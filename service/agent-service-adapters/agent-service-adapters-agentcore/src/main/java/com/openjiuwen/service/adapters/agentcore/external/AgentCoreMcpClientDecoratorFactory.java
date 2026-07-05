/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;

/**
 * Customization point for wrapping Core MCP clients with Service egress
 * behavior.
 *
 * @since 2026-06-24
 */
public interface AgentCoreMcpClientDecoratorFactory {
    /**
     * Wraps a Core MCP client with service-side external call behavior.
     *
     * @param config MCP server config
     * @param delegate Core MCP client to wrap
     * @param policy external call policy to apply
     * @return decorated MCP client
     */
    McpClient decorate(McpServerConfig config, McpClient delegate, AgentCoreExternalProperties.McpPolicy policy);
}
