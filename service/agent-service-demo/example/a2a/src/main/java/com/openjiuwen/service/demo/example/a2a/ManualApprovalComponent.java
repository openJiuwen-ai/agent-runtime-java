/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.WorkflowComponent;

import java.util.Map;
import java.util.Objects;

/**
 * Suspends an expense workflow for a deterministic human approval response.
 *
 * @since 0.1.0
 */
final class ManualApprovalComponent extends WorkflowComponent {
    @Override
    public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
        Map<?, ?> values = inputs instanceof Map<?, ?> map ? map : Map.of();
        String claimId = Objects.toString(values.get("claim_id"), "");
        String summary = Objects.toString(values.get("summary"), "");
        String question = "Agent D expense review requires manual approval for " + claimId
                + " because it exceeds policy. Reply 'approved' to approve or provide a rejection reason. Details: "
                + summary;
        Object userResponse = session.interact(question);
        return Map.of("user_response", Objects.toString(userResponse, ""));
    }
}
