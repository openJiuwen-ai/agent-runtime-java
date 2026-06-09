package com.openjiuwen.service.app.autoconfigure;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.config.QueryProperties;
import com.openjiuwen.service.app.config.RunnerLifecycle;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.orchestrator.DefaultServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@EnableConfigurationProperties({ServiceProperties.class, QueryProperties.class})
@ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
public class AgentServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service", name = "agent-id")
    public CoreAgentHandler coreAgentHandler(ServiceProperties properties) {
        return new CoreAgentHandler(properties.getAgentId());
    }

    @Bean
    @ConditionalOnBean(CoreAgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service", name = "auto-start-runner",
            havingValue = "true", matchIfMissing = true)
    public RunnerLifecycle runnerLifecycle() {
        return new RunnerLifecycle();
    }

    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    @ConditionalOnBean(AgentHandler.class)
    public ServeOrchestrator serveOrchestrator(AgentHandler agentHandler) {
        return new DefaultServeOrchestrator(agentHandler);
    }
}
