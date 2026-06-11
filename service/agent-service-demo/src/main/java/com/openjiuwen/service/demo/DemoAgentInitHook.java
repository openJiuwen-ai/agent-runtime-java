package com.openjiuwen.service.demo;

import com.openjiuwen.core.application.llm.LlmAgent;
import com.openjiuwen.core.application.schema.ConstrainConfig;
import com.openjiuwen.core.application.schema.LlmAgentConfig;
import com.openjiuwen.core.foundation.llm.schema.BaseModelInfo;
import com.openjiuwen.core.foundation.llm.schema.ModelConfig;
import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.lifecycle.AgentHandlerHolder;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;

import java.util.List;
import java.util.Map;

/**
 * Loads the demo Agent during init ({@code AgentInitHook}), aligned with Python {@code @app.init}.
 */
public class DemoAgentInitHook implements AgentInitHook {

    private static final String LLM_AGENT_ID = "demo-llm-agent";

    private final DemoLlmProperties llmProperties;
    private final AgentHandlerHolder handlerHolder;

    public DemoAgentInitHook(DemoLlmProperties llmProperties, AgentHandlerHolder handlerHolder) {
        this.llmProperties = llmProperties;
        this.handlerHolder = handlerHolder;
    }

    @Override
    public void onInit(AgentLifecycleContext context) throws Exception {
        if (!Boolean.FALSE.equals(llmProperties.getEnabled())) {
            ApiConfigLoader.load(llmProperties.getConfigFile(), llmProperties.isAutoDiscover())
                    .ifPresent(llmProperties::applyFromFile);
        }
        if (llmProperties.shouldUseLlm()) {
            handlerHolder.setHandler(new CoreAgentHandler(buildLlmAgent(llmProperties)));
        } else {
            handlerHolder.setHandler(new DemoAgentHandler());
        }
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
