package com.openjiuwen.a2a_service.agents.EDPAgent.stream;

import com.openjiuwen.core.session.interaction.InteractiveInput;

import java.util.Map;

public class InterruptResolver {

    public Map<String, Object> resolve(String interruptId, String userInput, String action) {
        if ("terminate".equals(action)) {
            return Map.of(
                    "status", "terminated",
                    "action", "terminate",
                    "content", "用户主动终止",
                    "user_feedback", userInput != null ? userInput : ""
            );
        }
        if ("retry".equals(action)) {
            return Map.of(
                    "status", "interrupt",
                    "action", "retry",
                    "content", "请重新输入"
            );
        }
        return Map.of(
                "status", "resume",
                "action", "resume",
                "user_feedback", userInput != null ? userInput : "",
                "interrupt_id", interruptId
        );
    }

    public InteractiveInput buildInteractiveInput(String toolCallId, String userFeedback) {
        InteractiveInput interactiveInput = new InteractiveInput();
        interactiveInput.update(toolCallId, userFeedback);
        return interactiveInput;
    }
}
