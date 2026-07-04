/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.List;
import java.util.Map;

/**
 * Calculator mock tool for Agent B. On the first call it triggers an ask-user
 * interrupt
 * (INPUT_REQUIRED). On resume, it rejects with the user's confirmation input as
 * the
 * synthetic result.
 *
 * @since 0.1.0
 */
public class CalcInterruptRail extends BaseInterruptRail {
    private static final String TOOL_NAME = "calc";

    public CalcInterruptRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder()
            .id(TOOL_NAME)
            .name(TOOL_NAME)
            .description("Perform mathematical calculations. Provide the expression to evaluate.")
            .inputParams(Map.of("type", "object", "properties", Map.of("expression",
                    Map.of("type", "string", "description", "The math expression to evaluate, e.g. '1+1'")), "required",
                List.of("expression")))
            .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            // Resume: return the user's confirmation as the synthetic calc result.
            return reject(String.valueOf(resumeInput));
        }
        // First call: ask-user interrupt (INPUT_REQUIRED).
        var request = InterruptRequest.builder()
            .message("Please confirm the calculation")
            .context(Map.of("_interrupt_kind", "ask_user"))
            .build();
        return interrupt(request);
    }
}
