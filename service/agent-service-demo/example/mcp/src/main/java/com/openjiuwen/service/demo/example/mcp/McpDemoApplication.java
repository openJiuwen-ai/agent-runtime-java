/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.mcp;

import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * External MCP feature demo — independent runnable module.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class McpDemoApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpDemoApplication.class);

    private static final String AGENT_ID = "demo-mcp-agent";

    public static void main(String[] args) {
        SpringApplication.run(McpDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider,
        ObjectProvider<AgentCoreExternalProperties> externalPropertiesProvider) {
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo MCP Agent",
            "ReAct agent with external MCP tools", llmConfig);
        bindMcpServers(agent, externalPropertiesProvider.getIfAvailable());
        return new JiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }

    private static void bindMcpServers(ReActAgent agent, AgentCoreExternalProperties externalProperties) {
        if (externalProperties == null) {
            return;
        }
        List<AgentCoreExternalProperties.McpServer> servers = externalProperties.getMcp().getServers();
        if (servers == null || servers.isEmpty()) {
            return;
        }
        for (AgentCoreExternalProperties.McpServer server : servers) {
            if (server.getServerName() == null || server.getServerName().isBlank()) {
                continue;
            }
            McpServerConfig config = McpServerConfig.builder().build();
            if (server.getServerId() != null && !server.getServerId().isBlank()) {
                config.setServerId(server.getServerId());
            }
            config.setServerName(server.getServerName());
            agent.getAbilityManager().add(config);
            LOGGER.info("Bound MCP server to agent ability manager, serverId={}, serverName={}",
                config.getServerId(), config.getServerName());
        }
    }
}
