/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.component.BranchComponent;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.llm.LLMComponent;
import com.openjiuwen.core.workflow.component.tool.ToolComponent;
import com.openjiuwen.core.workflow.component.tool.ToolComponentConfig;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Agent D's expense compliance and approval workflow.
 *
 * @since 0.1.0
 */
public final class ExpenseReviewWorkflow {
    static final String WORKFLOW_ID = "expense-review";

    static final String APPROVAL_NODE = "manual_approval";

    static final String FINAL_RESPONSE_NODE = "final_response";

    private ExpenseReviewWorkflow() {
    }

    /**
     * Builds the expense review workflow with the configured final-response model.
     *
     * @param llmConfig resolved model configuration for the final report
     * @return the configured expense review workflow
     */
    public static Workflow build(ResolvedLlmConfig llmConfig) {
        WorkflowCard card = WorkflowCard.builder().id(WORKFLOW_ID).name("Agent D Expense Review").version("1.0")
                .description("Validate an expense claim against policy and request approval when it exceeds the limit")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("query", Map.of("type", "string", "description", "Canonical JSON expense claim")),
                        "required", List.of("query")))
                .build();
        Workflow workflow = new Workflow(card);
        workflow.setStartComp("start", new Start(), Map.of("query", "${query}"), null);

        ToolComponent policy = new ToolComponent(new ToolComponentConfig()).bindTool(new ExpensePolicyTool());
        workflow.addWorkflowComp("check_policy", policy, Map.of("query", "${start.query}"), null);

        BranchComponent route = new BranchComponent();
        route.addBranch("${check_policy.data.requires_approval} == true", APPROVAL_NODE, "over_limit");
        route.addBranch("true", "auto_approve", "compliant");
        workflow.addWorkflowComp("route", route, Map.of("requires_approval", "${check_policy.data.requires_approval}"),
                null);

        workflow.addWorkflowComp(APPROVAL_NODE, new ManualApprovalComponent(),
                Map.of("claim_id", "${check_policy.data.claim_id}", "summary", "${check_policy.data.summary}"), null);

        workflow.addWorkflowComp(FINAL_RESPONSE_NODE, finalResponseComponent(llmConfig),
                Map.of("claim_id", "${check_policy.data.claim_id}", "policy_status",
                        "${check_policy.data.policy_status}", "approval_decision", "${manual_approval.user_response}",
                        "summary", "${check_policy.data.summary}"),
                null);

        ToolComponent autoApproval = new ToolComponent(new ToolComponentConfig()).bindTool(autoApprovalTool());
        workflow.addWorkflowComp("auto_approve", autoApproval, Map.of("claim_id", "${check_policy.data.claim_id}"),
                null);

        workflow.setEndComp("end", new End(),
                Map.of("claim_id", "${check_policy.data.claim_id}", "policy_status",
                        "${check_policy.data.policy_status}", "manual_decision", "${manual_approval.user_response}",
                        "auto_decision", "${auto_approve.data.decision}", "llm_report", "${final_response.text}",
                        "auto_report", "${check_policy.data.summary}"),
                null);

        workflow.addConnection("start", "check_policy");
        workflow.addConnection("check_policy", "route");
        workflow.addConnection(APPROVAL_NODE, FINAL_RESPONSE_NODE);
        workflow.addConnection(FINAL_RESPONSE_NODE, "end");
        workflow.addConnection("auto_approve", "end");
        return workflow;
    }

    private static LLMComponent finalResponseComponent(ResolvedLlmConfig config) {
        LLMCompConfig componentConfig = new LLMCompConfig();
        componentConfig.setModelClientConfig(ModelClientConfig.builder().clientProvider(config.getProvider())
                .apiKey(config.getApiKey()).apiBase(config.getApiBase())
                .timeout(config.getTimeout().toMillis() / 1000.0D).verifySsl(config.isSslVerify()).build());
        componentConfig.setModelConfig(ModelRequestConfig.builder().modelName(config.getModelName()).temperature(0.0D)
                .topP(config.getTopP()).maxTokens(256).build());
        componentConfig.setSystemPromptTemplate(new SystemMessage(
                "You format Agent D's final expense approval report. Respond in English only with one concise line. "
                        + "Preserve the claim ID, policy status, approval decision, and policy summary exactly."));
        componentConfig.setUserPromptTemplate(new UserMessage(
                "claim_id={{claim_id}}; policy_status={{policy_status}}; approval_decision={{approval_decision}}; "
                        + "summary={{summary}}"));
        componentConfig.setResponseFormat(new LinkedHashMap<>(Map.of("type", "text")));
        componentConfig.setOutputConfig(new LinkedHashMap<>(
                Map.of("text", Map.of("type", "string", "description", "Final English expense approval report"))));
        return new LLMComponent(componentConfig);
    }

    private static LocalFunction autoApprovalTool() {
        ToolCard card = ToolCard.builder().id("auto_approve_expense").name("auto_approve_expense")
                .description("Automatically approve a policy-compliant expense claim")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("claim_id", Map.of("type", "string", "description", "Expense claim identifier")),
                        "required", List.of("claim_id")))
                .build();
        return new LocalFunction(card, inputs -> Map.of("decision", "auto-approved"));
    }
}
