/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.interrupt.InterruptConstants;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.harness.rails.interrupt.ApproveResult;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import com.openjiuwen.harness.rails.interrupt.InterruptResult;
import com.openjiuwen.harness.rails.interrupt.RejectResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Runtime-side base rail that restores the template-method interrupt flow on
 * top of the harness {@link BaseInterruptRail}.
 *
 * <p>Subclasses implement {@link #resolveInterrupt} and return a decision:
 * approve keeps or rewrites the tool call, reject skips the tool and returns
 * the given tool result, interrupt raises a {@link ToolInterruptException} so
 * the ReAct loop enters the HITL resume flow. Declared tool cards are
 * registered on the owning agent when the rail is initialized so the model can
 * see them.
 *
 * @since 0.1.2
 */
public abstract class A2aInterruptRail extends BaseInterruptRail {
    private final List<ToolCard> toolCards;

    protected A2aInterruptRail(Collection<String> toolNames, List<ToolCard> toolCards) {
        super(toolNames);
        this.toolCards = toolCards == null ? List.of() : List.copyOf(toolCards);
    }

    @Override
    public void init(Object agent) {
        super.init(agent);
        if (agent instanceof BaseAgent baseAgent) {
            for (ToolCard card : toolCards) {
                baseAgent.getAbilityManager().add(card);
            }
        }
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (ctx == null || !(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        if (toolName == null || !getTools().contains(toolName)) {
            return;
        }
        ToolCall toolCall = inputs.getToolCall() instanceof ToolCall call ? call : null;
        String toolCallId = toolCall != null && toolCall.getId() != null ? toolCall.getId() : "";
        Object userInput = getUserInput(ctx, toolCallId);
        InterruptDecision decision = resolveInterrupt(ctx, toolCall, userInput);
        applyDecision(ctx, inputs, toolCall, decision);
    }

    /**
     * Resolves the decision for the current tool invocation.
     *
     * @param ctx callback context
     * @param toolCall current tool call
     * @param userInput resume input, when present
     * @return rail decision
     */
    protected abstract InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall,
        Object userInput);

    /**
     * Approves the tool call without rewriting its arguments.
     *
     * @return approve decision keeping the original args
     */
    protected InterruptDecision approve() {
        return new ApproveResult();
    }

    /**
     * Approves the tool call and rewrites its arguments.
     *
     * @param newArgs new JSON-encoded arguments
     * @return approve decision with rewritten args
     */
    protected InterruptDecision approve(String newArgs) {
        return new ApproveResult(newArgs);
    }

    /**
     * Rejects the tool call and substitutes the provided tool result.
     *
     * @param toolResult result returned in place of executing the tool
     * @return reject decision carrying the substituted result
     */
    protected InterruptDecision reject(Object toolResult) {
        return new RejectResult(toolResult);
    }

    /**
     * Triggers the HITL resume flow for the current tool call.
     *
     * @param request interrupt request payload
     * @return interrupt decision bound to the request
     */
    protected InterruptDecision interrupt(InterruptRequest request) {
        return new InterruptResult(request);
    }

    /**
     * Builds an interrupt request, optionally carrying extra context fields.
     *
     * @param message user-facing interrupt message
     * @param context extra context fields to attach, may be {@code null}
     * @return interrupt request ready to be wrapped by {@link #interrupt}
     */
    protected InterruptRequest buildInterruptRequest(String message, Map<String, Object> context) {
        InterruptRequest request = new InterruptRequest(message == null ? "" : message, null, "");
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                request.putExtraField(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return request;
    }

    private static Object getUserInput(AgentCallbackContext ctx, String toolCallId) {
        Object rawInput = ctx.getExtra() != null ? ctx.getExtra().get(InterruptConstants.RESUME_USER_INPUT_KEY) : null;
        if (!(rawInput instanceof InteractiveInput interactiveInput)) {
            return rawInput;
        }
        Map<String, Object> userInputs = interactiveInput.getUserInputs();
        if (toolCallId != null && !toolCallId.isEmpty() && userInputs.containsKey(toolCallId)) {
            return userInputs.get(toolCallId);
        }
        return interactiveInput.getRawInputs();
    }

    private static void applyDecision(AgentCallbackContext ctx, ToolCallInputs inputs, ToolCall toolCall,
        InterruptDecision decision) {
        if (decision instanceof ApproveResult approveResult) {
            if (approveResult.newArgs() != null) {
                inputs.setToolArgs(approveResult.newArgs());
            }
            return;
        }
        if (decision instanceof RejectResult rejectResult) {
            ctx.getExtra().put("_skip_tool", Boolean.TRUE);
            inputs.setToolResult(rejectResult.toolResult());
            String toolCallId = toolCall != null && toolCall.getId() != null ? toolCall.getId() : "";
            ToolMessage toolMessage = rejectResult.toolMessage() != null ? rejectResult.toolMessage()
                : new ToolMessage(String.valueOf(rejectResult.toolResult()), toolCallId,
                    toolCall != null && toolCall.getName() != null ? toolCall.getName() : "");
            inputs.setToolMsg(toolMessage);
            return;
        }
        if (decision instanceof InterruptResult interruptResult) {
            throw new ToolInterruptException(interruptResult.request(), toolCall);
        }
    }
}
