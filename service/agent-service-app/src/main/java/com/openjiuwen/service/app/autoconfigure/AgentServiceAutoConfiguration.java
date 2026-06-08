package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.orchestrator.DefaultServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AgentServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandler coreAgentHandler() {
        return new CoreAgentHandler();
    }

    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    public ServeOrchestrator serveOrchestrator(AgentHandler agentHandler) {
        return new DefaultServeOrchestrator(agentHandler);
    }
}
