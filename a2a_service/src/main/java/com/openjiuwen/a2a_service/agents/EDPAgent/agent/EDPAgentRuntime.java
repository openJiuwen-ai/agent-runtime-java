package com.openjiuwen.a2a_service.agents.EDPAgent.agent;

import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.a2a_service.agents.EDPAgent.stream.NorthboundSseService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class EDPAgentRuntime {

    private final EDPAgentFactory factory;
    private ReActAgent agent;

    public EDPAgentRuntime() {
        this(new EDPAgentFactory());
    }

    public EDPAgentRuntime(EDPAgentFactory factory) {
        this.factory = factory;
    }

    public synchronized ReActAgent initialize(Path agentRulePath) throws IOException {
        if (agent == null) {
            agent = factory.create(agentRulePath);
        }
        return agent;
    }

    public synchronized ReActAgent getAgent() {
        if (agent == null) {
            throw new IllegalStateException("EDPAgentRuntime is not initialized");
        }
        return agent;
    }

    public List<String> streamSse(String query, String conversationId) {
        return new NorthboundSseService(getAgent()).stream(query, conversationId);
    }

    public List<String> streamSse(String query, String conversationId, java.util.Map<String, Object> originalBody) {
        return new NorthboundSseService(getAgent()).stream(query, conversationId, originalBody);
    }

    public List<String> resumeSse(InteractiveInput interactiveInput, String conversationId) {
        return new NorthboundSseService(getAgent()).resume(interactiveInput, conversationId);
    }
}
