/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moaagent.agent.discovery.CandidatePack;
import com.moaagent.agent.routing.cardinality.ModelTaskCardinalityAnalysis;
import com.moaagent.agent.routing.cardinality.TaskCardinalityAnalysis;
import com.moaagent.agent.routing.recognition.IntentRecognition;
import com.moaagent.agent.routing.recognition.PackIntentRecognition;
import com.moaagent.agent.routing.policy.DefaultRequestRoutingPolicy;
import com.moaagent.agent.routing.policy.RequestRoutingPolicy;
import com.moaagent.agent.skill.SkillScriptRunner;
import com.moaagent.demo.mock.MockBusinessService;
import com.moaagent.runtime.leadout.ServiceLeadOutProperties;
import com.moaagent.runtime.skill.NativeSkillProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Verifies the default MOA wiring without a sandbox or model calls.
 *
 * @since 0.1.3
 */
class DemoProfileTest {
    @Test
    void bothMoaRolesUseDefaultRoutingAndA2aOnlySkillsWithoutSandbox() {
        for (String role : new String[]{"l1", "l2"}) {
            try (var context = new SpringApplicationBuilder(MoaAgentDemoApplication.class)
                    .run(
                    "--spring.profiles.active=" + role, "--server.port=0",
                    "--LLM_PROVIDER=OpenAI", "--LLM_API_KEY=dummy",
                    "--LLM_API_BASE=http://127.0.0.1:9", "--LLM_MODEL=dummy",
                    "--openjiuwen.service.a2a.remote-agents=", "--logging.level.root=ERROR")) {
                assertThat(context.getBeansOfType(MockBusinessService.class)).isEmpty();
                assertThat(context.getBean(AgentHandler.class)).isNotNull();
                assertThat(context.getBean(IntentRecognition.class)).isInstanceOf(PackIntentRecognition.class);
                assertThat(context.getBean(TaskCardinalityAnalysis.class))
                        .isInstanceOf(ModelTaskCardinalityAnalysis.class);
                assertThat(context.getBean(RequestRoutingPolicy.class)).isInstanceOf(DefaultRequestRoutingPolicy.class);
                assertThat(context.getBean(CandidatePack.class).cards()).hasSize("l1".equals(role) ? 3 : 1);
                NativeSkillProperties skills = context.getBean(NativeSkillProperties.class);
                assertThat(skills.agentId()).isEqualTo("l1".equals(role) ? "travel-skill" : "hotel-skill");
                assertThat(skills.operations().values()).allSatisfy(operation -> {
                    assertThat(operation.type().name()).isEqualTo("A2A");
                    assertThat(operation.beforeScript()).isNull();
                    assertThat(operation.resultScript()).isNull();
                });
                assertThatThrownBy(() -> context.getBean(SkillScriptRunner.class).run(null, "", java.util.Map.of()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("only supports A2A");
                var rules = context.getBean(ServiceLeadOutProperties.class).rules();
                assertThat(rules).hasSize("l1".equals(role) ? 1 : 0);
            }
        }
    }
}
