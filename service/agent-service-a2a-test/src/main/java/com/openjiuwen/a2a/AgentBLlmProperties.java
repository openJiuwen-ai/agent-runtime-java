/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.a2a;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "openjiuwen.agentb.llm")
public class AgentBLlmProperties {

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "";
    private String modelName = "";
    private boolean sslVerify = true;
    private String systemPrompt = "You are a helpful assistant. Answer concisely and accurately.";
    private Double temperature = 0.6;
    private Double topP = 0.8;
    private Duration timeout = Duration.ofSeconds(60);
    private int contextWindowLimit = 10;

    boolean isConfigured() {
        return hasText(apiKey) && hasText(apiBase) && hasText(modelName);
    }

    void requireConfigured() {
        requireText(apiKey, "openjiuwen.agentb.llm.api-key");
        requireText(apiBase, "openjiuwen.agentb.llm.api-base");
        requireText(modelName, "openjiuwen.agentb.llm.model-name");
    }

    private static void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required for Agent B");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
