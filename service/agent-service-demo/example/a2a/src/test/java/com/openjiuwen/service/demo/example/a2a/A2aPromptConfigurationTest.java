/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.config.llm.LlmProperties;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Verifies that each A2A application overrides the shared LLM prompt through
 * the configuration prefix consumed by {@link LlmProperties}.
 */
class A2aPromptConfigurationTest {
    private static final String SHARED_CONFIG = "application-base.yml";

    private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

    @ParameterizedTest
    @MethodSource("agentPrompts")
    void agentPromptOverridesSharedPrompt(String agentConfig, List<String> expectedFragments) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        addFirst(propertySources, SHARED_CONFIG);
        addFirst(propertySources, agentConfig);

        LlmProperties properties = new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("openjiuwen.service.llm", Bindable.of(LlmProperties.class))
                .orElseThrow(() -> new AssertionError("LLM properties were not bound for " + agentConfig));

        assertThat(properties.getSystemPrompt()).contains(expectedFragments.toArray(String[]::new))
                .doesNotContain("You are a helpful assistant");
        assertThat(properties.getTemperature()).isEqualTo(0.1D);
        assertThat(propertySources.stream().map(source -> source.getProperty("openjiuwen.demo.llm.system-prompt"))
                .filter(value -> value != null)).isEmpty();
    }

    private static void addFirst(MutablePropertySources propertySources, String resourceName) throws IOException {
        List<PropertySource<?>> loaded = YAML_LOADER.load(resourceName, new ClassPathResource(resourceName));
        for (int index = loaded.size() - 1; index >= 0; index--) {
            propertySources.addFirst(loaded.get(index));
        }
    }

    private static Stream<Arguments> agentPrompts() {
        return Stream.of(
                Arguments.of("application-a2a-agent-a.yml",
                        List.of("Agent A", "delegate_to_agentb", "do not answer directly", "verbatim")),
                Arguments.of("application-a2a-agent-b.yml",
                        List.of("Agent B", "delegate_to_agentc_streaming", "delegate_to_agentc_nonstreaming",
                                "review_expense_streaming", "review_expense_nonstreaming", "verbatim")),
                Arguments.of("application-a2a-agent-c.yml",
                        List.of("Agent C", "food_recommend", "before answering", "English only")));
    }
}
