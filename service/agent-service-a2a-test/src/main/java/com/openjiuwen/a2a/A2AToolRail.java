package com.openjiuwen.a2a;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Rail for Agent A: intercepts "delegate_to_agentb" tool calls.
 * The agentName alone is sufficient — the Orchestrator resolves the URL
 * from the remote agent registry.
 */
public class A2AToolRail extends BaseInterruptRail {

    private static final Logger log = LoggerFactory.getLogger(A2AToolRail.class);

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final String TOOL_NAME = "delegate_to_agentb";
    private static final String AGENT_NAME = "agentb";

    public A2AToolRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder()
                .id(TOOL_NAME).name(TOOL_NAME)
                .description("Delegate a task to Agent B for processing — use for calculations, hotel searches, or any task Agent B can handle")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of(
                                        "type", "string",
                                        "description", "The request to forward to Agent B's LLM")),
                        "required", List.of("message")))
                .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall,
                                                  Object resumeInput) {
        if (resumeInput != null) {
            // Resume: skip the empty tool and return the remote result directly.
            // approve() would let tool execution continue → "Tool instance not found".
            return reject(resumeInput);
        }
        // Extract user's real query from tool call arguments
        String userQuery = null;
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object msg = args.get("message");
            if (msg instanceof String s && !s.isBlank()) userQuery = s;
        } catch (Exception ignored) {
            // arguments parse failed; fall through to AGENT_NAME
        }
        // Trigger interrupt: user query as message, agentName in context for routing
        var request = InterruptRequest.builder()
                .message(userQuery != null ? userQuery : AGENT_NAME)
                .context(Map.of("agentName", AGENT_NAME,
                        "_interrupt_kind", "a2a_delegate",
                        "_stream_mode", "sse"))
                .build();
        return interrupt(request);
    }
}
