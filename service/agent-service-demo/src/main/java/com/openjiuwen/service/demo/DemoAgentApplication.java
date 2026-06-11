package com.openjiuwen.service.demo;

import com.openjiuwen.service.app.lifecycle.AgentHandlerHolder;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(DemoLlmProperties.class)
public class DemoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoAgentApplication.class, args);
    }

    @Bean
    AgentInitHook demoAgentInitHook(DemoLlmProperties llmProperties, AgentHandlerHolder handlerHolder) {
        return new DemoAgentInitHook(llmProperties, handlerHolder);
    }
}
