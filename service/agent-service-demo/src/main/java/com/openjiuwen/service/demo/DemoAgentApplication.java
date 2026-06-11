package com.openjiuwen.service.demo;

import com.openjiuwen.core.application.llm.LlmAgent;
import com.openjiuwen.core.application.schema.ConstrainConfig;
import com.openjiuwen.core.application.schema.LlmAgentConfig;
import com.openjiuwen.core.foundation.llm.schema.BaseModelInfo;
import com.openjiuwen.core.foundation.llm.schema.ModelConfig;
import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

@SpringBootApplication
@EnableConfigurationProperties(DemoLlmProperties.class)
public class DemoAgentApplication {

    private static final String LLM_AGENT_ID = "demo-llm-agent";

    public static void main(String[] args) {
        SpringApplication.run(DemoAgentApplication.class, args);
    }

    @Bean
    AgentHandler demoAgentHandler(DemoLlmProperties llmProperties) {
        if (!Boolean.FALSE.equals(llmProperties.getEnabled())) {
            ApiConfigLoader.load(llmProperties.getConfigFile(), llmProperties.isAutoDiscover())
                    .ifPresent(llmProperties::applyFromFile);
        }
        if (llmProperties.shouldUseLlm()) {
            return new CoreAgentHandler(buildLlmAgent(llmProperties));
        }
        return new DemoAgentHandler();
    }

    private static LlmAgent buildLlmAgent(DemoLlmProperties llmProperties) {
        llmProperties.requireConfigured();
        BaseModelInfo modelInfo = BaseModelInfo.builder()
                .apiKey(llmProperties.getApiKey())
                .apiBase(llmProperties.getApiBase())
                .modelName(llmProperties.getModelName())
                .temperature(llmProperties.getTemperature())
                .topP(llmProperties.getTopP())
                .timeout(timeoutSeconds(llmProperties))
                .verifySsl(llmProperties.isSslVerify())
                .build();
        LlmAgentConfig config = LlmAgentConfig.builder()
                .id(LLM_AGENT_ID)
                .version("0.1.0")
                .description("Demo LLM agent for Agent Service")
                .model(new ModelConfig(llmProperties.getProvider(), modelInfo))
                .promptTemplate(List.of(Map.of("role", "system", "content", llmProperties.getSystemPrompt())))
                .constrain(ConstrainConfig.builder()
                        .reservedMaxChatRounds(llmProperties.getContextWindowLimit())
                        .build())
                .build();
        return new LlmAgent(config);
    }

    private static int timeoutSeconds(DemoLlmProperties llmProperties) {
        long seconds = llmProperties.getTimeout().toSeconds();
        if (seconds <= 0) {
            return 60;
        }
        return Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
    }
}
