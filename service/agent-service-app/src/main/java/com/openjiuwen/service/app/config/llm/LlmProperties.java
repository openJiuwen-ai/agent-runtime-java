/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw LLM configuration bound from {@code openjiuwen.service.llm}.
 * <p>
 * The API key remains in its configured form in this bean. Consumers should use
 * {@link LlmConfigResolver} to obtain a resolved and decrypted configuration.
 * </p>
 *
 * @since 0.1.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openjiuwen.service.llm")
public class LlmProperties {
    private boolean isModelSelectionEnabled;
    private String configFile;
    private Boolean autoDiscover;
    private String modelId;
    private String provider;
    private String apiKey;
    private String apiBase;
    private String modelName;
    private Boolean sslVerify;
    private String systemPrompt;
    private Double temperature;
    private Double topP;
    private Duration timeout;
    private Duration connectTimeout;
    private Duration streamIdleSeconds;
    private Integer contextWindowLimit;
    private Integer maxIterations;

    private List<ModelProperties> models = new ArrayList<>();

    /**
     * Additional deployment models. The existing root connection remains the default;
     * sampling parameters and timeout are shared with it.
     */
    @Getter
    @Setter
    public static class ModelProperties {
        private String modelId;
        private String provider;
        private String apiKey;
        private String apiBase;
        private String modelName;
        private Boolean sslVerify;
    }
}
