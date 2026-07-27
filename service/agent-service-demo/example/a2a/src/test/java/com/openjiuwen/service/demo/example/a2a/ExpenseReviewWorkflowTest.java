/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowExecutionState;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.workflow.WorkflowSessions;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

/**
 * Executes both branches of Agent D's expense review workflow.
 */
class ExpenseReviewWorkflowTest {
    @Test
    void compliantClaimCompletesWithAutomaticApproval() {
        Workflow workflow = ExpenseReviewWorkflow.build(testLlmConfig());

        WorkflowOutput output = workflow.invoke(Map.of("query", claim("WF-AUTO", "transport", 100, 1, 100)),
                newSession("auto"), null);

        assertThat(output.getState()).isEqualTo(WorkflowExecutionState.COMPLETED);
        assertThat(String.valueOf(output.getResult())).contains("Agent D expense review completed").contains("WF-AUTO")
                .contains("COMPLIANT").contains("auto-approved");
    }

    @Test
    void overLimitClaimStopsBeforeRealLlmUntilManualApproval() {
        Workflow workflow = ExpenseReviewWorkflow.build(testLlmConfig());
        WorkflowSessionApi session = newSession("manual");

        WorkflowOutput first = workflow.invoke(Map.of("query", claim("WF-HIGH", "hotel", 1000, 3, 3000)), session,
                null);

        assertThat(first.getState()).isEqualTo(WorkflowExecutionState.INPUT_REQUIRED);
        assertThat(first.getResult()).isNotNull();
    }

    private static WorkflowSessionApi newSession(String suffix) {
        return WorkflowSessions.createWorkflowSession("agent-d-test-" + suffix);
    }

    private static String claim(String id, String category, double unitPrice, double quantity, double total) {
        return String.format(
                "{\"claim_id\":\"%s\",\"category\":\"%s\",\"unit_price\":%.1f,"
                        + "\"quantity\":%.1f,\"total\":%.1f,\"currency\":\"CNY\"}",
                id, category, unitPrice, quantity, total);
    }

    private static ResolvedLlmConfig testLlmConfig() {
        return ResolvedLlmConfig.builder().provider("OpenAI").apiKey("unused-test-key").apiBase("http://127.0.0.1:1/v1")
                .modelName("unused-test-model").sslVerify(false).temperature(0.0D).topP(1.0D)
                .timeout(Duration.ofSeconds(1)).contextWindowLimit(4096).maxIterations(2).build();
    }
}
