package com.openjiuwen.a2a;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * Agent A: a real ReAct LLM agent that knows about Agent B as a remote agent.
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(AgentALlmProperties.class)
public class AgentAApp {

    private static final String AGENT_ID = "a2a-agent-a";

    public static void main(String[] args) {
        SpringApplication.run(AgentAApp.class, args);
    }

    @Bean
    AgentHandler agentAHandler(AgentALlmProperties props) {
        props.requireConfigured();
        return new JiuwenCoreAgentHandler(buildReActAgent(props));
    }

    private static ReActAgent buildReActAgent(AgentALlmProperties props) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name("AgentA")
                .description("A2A Agent A — real ReAct LLM agent")
                .build();

        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getContextWindowLimit())
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt())))
                .build()
                .configureModelClient(props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        config.getModelConfigObj().setTemperature(props.getTemperature());
        config.getModelConfigObj().setTopP(props.getTopP());
        agent.configure(config);

        // A2A Tool Rail: intercepts "delegate_to_agentb" → triggers interrupt → Orchestrator calls Agent B
        agent.registerRail(new A2AToolRail());

        return agent;
    }
}
