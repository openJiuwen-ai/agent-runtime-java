package com.openjiuwen.a2a_service.agents.EDPAgent.api;

import com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentRuntime;
import com.openjiuwen.a2a_service.agents.EDPAgent.stream.InterruptResolver;

import java.util.List;
import java.util.Map;

public class NorthboundApiService {

    private final EDPAgentRuntime runtime;
    private final InterruptResolver interruptResolver;

    public NorthboundApiService(EDPAgentRuntime runtime) {
        this(runtime, new InterruptResolver());
    }

    public NorthboundApiService(EDPAgentRuntime runtime, InterruptResolver interruptResolver) {
        this.runtime = runtime;
        this.interruptResolver = interruptResolver;
    }

    public List<String> invoke(InvokeRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String conversationId = request.getConversationId() != null ? request.getConversationId() : "";
        return runtime.streamSse(
                request.getQuery(),
                conversationId,
                Map.of(
                        "query", request.getQuery(),
                        "conversation_id", conversationId,
                        "custom_data", request.getCustomData()
                )
        );
    }

    public Map<String, Object> resolveInterrupt(String interruptId, InterruptResolveRequest request) {
        String action = request != null ? request.getAction() : "resume";
        if (!"resume".equals(action) && !"terminate".equals(action) && !"retry".equals(action)) {
            throw new IllegalArgumentException("Invalid action: " + action);
        }
        Map<String, Object> result = interruptResolver.resolve(
                interruptId,
                request != null ? request.getUserInput() : "",
                action
        );
        if ("terminated".equals(result.get("status"))) {
            return Map.of("status", "terminated", "message", "会话已终止");
        }
        if ("resume".equals(result.get("status"))) {
            return Map.of(
                    "status", "resumed",
                    "interrupt_id", interruptId,
                    "user_feedback", request != null ? request.getUserInput() : ""
            );
        }
        return result;
    }

    public Map<String, Object> health() {
        return Map.of("status", "healthy", "service", "EDPAgent");
    }
}
