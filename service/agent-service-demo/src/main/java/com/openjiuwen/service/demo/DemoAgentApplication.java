/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.demo.example.support.DemoLlmProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * Minimal end-to-end Agent Service example.
 * <p>
 * It wires a single {@link AgentHandler} backed by the agent-core-java runtime
 * ({@link JiuwenCoreAgentHandler} delegating to a {@link ReActAgent}). The
 * ReAct loop
 * (reason → act → observe) lets the agent actually invoke registered tools,
 * which is what
 * makes the MCP external-service integration meaningful. With that one bean the
 * framework
 * auto-exposes the full service surface:
 * </p>
 * <ul>
 * <li>{@code GET /health} — process / agent readiness probe</li>
 * <li>{@code POST /v1/query} (and legacy {@code /query}) — non-streaming and
 * SSE chat</li>
 * <li>{@code POST /v1/reset_conversation} — cancel active streams and clear the
 * session</li>
 * </ul>
 * <p>
 * Internal service (Redis checkpointer) is configured under
 * {@code openjiuwen.service.middleware}; the external service (MCP tools) is
 * registered through
 * {@link ExternalSvcAdapterRegistrar} when an MCP server is configured under
 * {@code openjiuwen.service.external.mcp}.
 * </p>
 */
@SpringBootApplication
@EnableConfigurationProperties(DemoLlmProperties.class)
public class DemoAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentApplication.class);
    private static final String REACT_AGENT_ID = "demo-react-agent";

    public static void main(String[] args) {
        SpringApplication.run(DemoAgentApplication.class, args);
    }

    @Bean
    AgentHandler demoAgentHandler(DemoLlmProperties llmProperties,
            ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider,
            ObjectProvider<AgentCoreExternalProperties> externalPropertiesProvider) {
        llmProperties.applyApiConfigIfPresent();
        ReActAgent agent = buildReActAgent(llmProperties);
        bindMcpServers(agent, externalPropertiesProvider.getIfAvailable());
        return new JiuwenCoreAgentHandler(agent,
                externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }

    /**
     * Binds each configured MCP server to the agent's ability manager so its tools
     * are exposed to
     * the ReAct loop. The transport details (server-path / client-type) live in the
     * Runner-side
     * registration; the ability manager only needs {@code serverId} +
     * {@code serverName} to fetch
     * that server's tools lazily the first time the agent builds its tool list.
     *
     * @param agent the ReAct agent to bind tools to
     * @param externalProperties external service properties, may be {@code null}
     */
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
            log.info("Bound MCP server to agent ability manager, serverId={}, serverName={}", config.getServerId(),
                    config.getServerName());
        }
    }

    private static ReActAgent buildReActAgent(DemoLlmProperties llmProperties) {
        llmProperties.requireConfigured();
        AgentCard card = AgentCard.builder().id(REACT_AGENT_ID).name(REACT_AGENT_ID)
                .description("Demo ReAct agent for Agent Service").build();
        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", llmProperties.getSystemPrompt())))
                .maxIterations(llmProperties.getMaxIterations()).build()
                .configureModelClient(llmProperties.getProvider(), llmProperties.getApiKey(),
                        llmProperties.getApiBase(), llmProperties.getModelName(), llmProperties.isSslVerify())
                .configureContextEngine(null, llmProperties.getContextWindowLimit(), false);
        ModelRequestConfig requestConfig = config.getModelConfigObj();
        requestConfig.setTemperature(llmProperties.getTemperature());
        requestConfig.setTopP(llmProperties.getTopP());
        agent.configure(config);
        return agent;
    }
}
