/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.service.app.config.llm.LlmProperties;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.app.config.runbudget.RunBudgetProperties;
import com.openjiuwen.service.app.config.runbudget.RunBudgetWiring;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * UT-C02~C05 runtime side: FEAT-057 factory wiring gating (explicit legacy max-iterations
 * wins), the raised fallback envelope, zero-as-undeclared normalization and fail-fast
 * validation of illegal budget values.
 */
class ExampleReActAgentFactoryBudgetWiringTest {
    @Test
    void build_noWiring_keepsLegacyMaxIterations() {
        // Given no wiring at all
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(4));

        // When / Then the legacy loop limit applies unchanged
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(4);
    }

    @Test
    void build_turnEnabledNoLimit_raisesEnvelopeToHardLimitPlusOne() {
        // Given turn budget enabled and no explicit llm.max-iterations
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        LlmProperties llm = new LlmProperties();

        // When building with wiring
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(5),
            new RunBudgetWiring(llm, budget));

        // Then the envelope rises to max(30 * 2, 200) + 1
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(201);
    }

    @Test
    void build_turnEnabledWithExplicitLegacyLimit_keepsHardTruncation() {
        // Given turn budget enabled but llm.max-iterations explicitly set to 5
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        LlmProperties llm = new LlmProperties();
        llm.setMaxIterations(5);

        // When building with wiring
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(5),
            new RunBudgetWiring(llm, budget));

        // Then the explicit legacy limit wins over the elastic envelope
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(5);
    }

    @Test
    void build_suggestedRoundsAboveHundred_scalesEnvelopeByGuarantee() {
        // Given a 150-round guarantee (hard limit max(300, 200) = 300)
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        budget.getTurn().setSuggestedRounds(150);

        // When building
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(5),
            new RunBudgetWiring(new LlmProperties(), budget));

        // Then the envelope follows the guarantee-scaled hard limit
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(301);
    }

    @Test
    void build_explicitHardLimit_scalesEnvelopeByHardLimit() {
        // Given an explicit hard limit 50
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        budget.getTurn().setHardLimit(50);

        // When building
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(5),
            new RunBudgetWiring(new LlmProperties(), budget));

        // Then the envelope is hardLimit + 1
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(51);
    }

    @Test
    void build_timeOnly_keepsLegacyMaxIterations() {
        // Given only the time dimension declared
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTime().setTotalBudgetSeconds(120);

        // When building
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(7),
            new RunBudgetWiring(new LlmProperties(), budget));

        // Then the loop limit is untouched
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(7);
    }

    @Test
    void build_zeroOptionalValues_normalizedAsUndeclared() {
        // Given zero-valued optional fields (yaml "0 = not declared" convention)
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        budget.getTurn().setSuggestedRounds(0);
        budget.getTurn().setHardLimit(0);

        // When building, Then zeros normalize to undeclared and defaults apply
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(5),
            new RunBudgetWiring(new LlmProperties(), budget));
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(201);
    }

    @Test
    void build_hardLimitBelowGuarantee_failsFast() {
        // Given an illegal hard limit below the guarantee
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);
        budget.getTurn().setSuggestedRounds(30);
        budget.getTurn().setHardLimit(20);

        // When building, Then wiring fails fast with IllegalStateException
        RunBudgetWiring wiring = new RunBudgetWiring(new LlmProperties(), budget);
        ResolvedLlmConfig config = legacyConfig(5);
        assertThatThrownBy(() -> ExampleReActAgentFactory.build("agent", "Agent", "desc", config, wiring))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openjiuwen.service.run-budget");
    }

    @Test
    void build_negativeTimeBudget_failsFast() {
        // Given a negative total time budget
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTime().setTotalBudgetSeconds(-1);

        // When building, Then wiring fails fast
        RunBudgetWiring wiring = new RunBudgetWiring(new LlmProperties(), budget);
        ResolvedLlmConfig config = legacyConfig(5);
        assertThatThrownBy(() -> ExampleReActAgentFactory.build("agent", "Agent", "desc", config, wiring))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openjiuwen.service.run-budget");
    }

    @Test
    void build_nullLlmProperties_turnDimensionNeverActivates() {
        // Given turn enabled but no raw LlmProperties to prove the limit is undeclared
        RunBudgetProperties budget = new RunBudgetProperties();
        budget.getTurn().setEnabled(true);

        // When building, Then the turn dimension conservatively stays off
        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "desc", legacyConfig(6),
            new RunBudgetWiring(null, budget));
        assertThat(agentConfig(agent).getMaxIterations()).isEqualTo(6);
    }

    private static ReActAgentConfig agentConfig(ReActAgent agent) {
        Object rawConfig = agent.getConfig();
        if (!(rawConfig instanceof ReActAgentConfig agentConfig)) {
            throw new AssertionError("ReActAgent must expose a ReActAgentConfig");
        }
        return agentConfig;
    }

    private static ResolvedLlmConfig legacyConfig(int maxIterations) {
        return ResolvedLlmConfig.builder()
            .provider("OpenAI")
            .apiKey("test-key")
            .apiBase("https://localhost/v1")
            .modelName("test-model")
            .sslVerify(false)
            .systemPrompt("Test prompt")
            .temperature(0.2D)
            .topP(0.7D)
            .timeout(Duration.ofMillis(1500))
            .contextWindowLimit(12)
            .maxIterations(maxIterations)
            .build();
    }
}
