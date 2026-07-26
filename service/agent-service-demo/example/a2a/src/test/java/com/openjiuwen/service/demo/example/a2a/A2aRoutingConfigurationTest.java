/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.openjiuwen.service.app.config.A2AProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

/**
 * Verifies the checked-in A2A routing and Agent Card configuration.
 */
class A2aRoutingConfigurationTest {
    private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

    @Test
    void agentAUsesConfiguredStreamingAgentBRoute() throws IOException {
        A2AProperties properties = bind("application-a2a-agent-a.yml");

        assertThat(properties.getRemoteAgents())
                .extracting(A2AProperties.RemoteAgentProperties::getName, A2AProperties.RemoteAgentProperties::getUrl,
                        A2AProperties.RemoteAgentProperties::isStreaming)
                .containsExactly(tuple("agentb", "http://localhost:18091/", true));
        assertThat(properties.getSkills()).extracting(A2AProperties.SkillProperties::getId)
                .contains("delegate_to_agentb").doesNotContain("delegate_to_agentb_sync");
    }

    @Test
    void agentBRegistersStreamingAndNonStreamingRoutesForAgentCAndAgentD() throws IOException {
        A2AProperties properties = bind("application-a2a-agent-b.yml");

        assertThat(properties.getRemoteAgents())
                .extracting(A2AProperties.RemoteAgentProperties::getName, A2AProperties.RemoteAgentProperties::getUrl,
                        A2AProperties.RemoteAgentProperties::isStreaming)
                .containsExactly(tuple("agentc-streaming", "http://localhost:18092/", true),
                        tuple("agentc-nonstreaming", "http://localhost:18092/", false),
                        tuple("agentd-streaming", "http://localhost:18093/", true),
                        tuple("agentd-nonstreaming", "http://localhost:18093/", false));
        assertThat(properties.getSkills()).extracting(A2AProperties.SkillProperties::getId).contains(
                "delegate_to_agentc_streaming", "delegate_to_agentc_nonstreaming", "review_expense_streaming",
                "review_expense_nonstreaming");
    }

    @Test
    void agentDAdvertisesExpenseReviewWorkflow() throws IOException {
        A2AProperties properties = bind("application-a2a-agent-d.yml");

        assertThat(properties.getAgentDescription()).contains("Agent D").contains("WorkflowAgent");
        assertThat(properties.getSkills()).extracting(A2AProperties.SkillProperties::getId)
                .containsExactly("review_expense");
    }

    private static A2AProperties bind(String resourceName) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        List<PropertySource<?>> loaded = YAML_LOADER.load(resourceName, new ClassPathResource(resourceName));
        for (int index = loaded.size() - 1; index >= 0; index--) {
            propertySources.addFirst(loaded.get(index));
        }
        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("openjiuwen.service.a2a", Bindable.of(A2AProperties.class))
                .orElseThrow(() -> new AssertionError("A2A properties were not bound for " + resourceName));
    }
}
