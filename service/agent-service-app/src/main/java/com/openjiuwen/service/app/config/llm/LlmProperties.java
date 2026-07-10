/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
    private String configFile;

    private Boolean shouldAutoDiscover;

    private String provider;

    private String apiKey;

    private String apiBase;

    private String modelName;

    private Boolean shouldVerifySsl;

    private String systemPrompt;

    private Double temperature;

    private Double topP;

    private Duration timeout;

    private Integer contextWindowLimit;

    private Integer maxIterations;

    /**
     * Returns the auto-discovery setting using the external configuration name.
     *
     * @return whether API configuration auto-discovery is enabled
     */
    public Boolean getAutoDiscover() {
        return shouldAutoDiscover;
    }

    /**
     * Sets the auto-discovery setting using the external configuration name.
     *
     * @param shouldAutoDiscoverValue whether API configuration auto-discovery is enabled
     */
    public void setAutoDiscover(Boolean shouldAutoDiscoverValue) {
        shouldAutoDiscover = shouldAutoDiscoverValue;
    }

    /**
     * Returns the TLS verification setting using the external configuration name.
     *
     * @return whether TLS certificates should be verified
     */
    public Boolean getSslVerify() {
        return shouldVerifySsl;
    }

    /**
     * Sets the TLS verification setting using the external configuration name.
     *
     * @param shouldVerifySslValue whether TLS certificates should be verified
     */
    public void setSslVerify(Boolean shouldVerifySslValue) {
        shouldVerifySsl = shouldVerifySslValue;
    }
}
