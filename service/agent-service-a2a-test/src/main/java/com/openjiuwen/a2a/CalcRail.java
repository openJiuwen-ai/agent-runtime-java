/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.a2a;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calculator mock tool for Agent B. First call: ask-user interrupt (INPUT_REQUIRED). Resume call: reject with synthetic
 * result.
 */
public class CalcRail extends BaseInterruptRail {

    private static final Logger log = LoggerFactory.getLogger(CalcRail.class);
    private static final String TOOL_NAME = "calc";

    public CalcRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
                .description("Perform mathematical calculations. Provide the expression to evaluate.")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("expression",
                                Map.of("type", "string", "description", "The math expression to evaluate, e.g. '1+1'")),
                        "required", List.of("expression")))
                .build();
        getTools().add(card);
        log.info("CalcRail registered tool={}", TOOL_NAME);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        if (resumeInput != null) {
            String result = String.valueOf(resumeInput);
            log.info("CalcRail resume: returning user input as result. resumeInput={}", result);
            return reject(result);
        }
        // First call: ask-user interrupt (INPUT_REQUIRED)
        log.info("CalcRail: intercepting '{}' -> ask_user interrupt", toolCall != null ? toolCall.getName() : "null");
        var request = InterruptRequest.builder().message("Please confirm the calculation")
                .context(Map.of("_interrupt_kind", "ask_user")).build();
        return interrupt(request);
    }
}
