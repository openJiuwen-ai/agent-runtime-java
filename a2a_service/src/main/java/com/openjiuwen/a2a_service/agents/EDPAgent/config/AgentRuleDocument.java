package com.openjiuwen.a2a_service.agents.EDPAgent.config;

public class AgentRuleDocument {

    private final AgentRuleConfig config;
    private final String promptBody;

    public AgentRuleDocument(AgentRuleConfig config, String promptBody) {
        this.config = config;
        this.promptBody = promptBody != null ? promptBody : "";
    }

    public AgentRuleConfig getConfig() {
        return config;
    }

    public String getPromptBody() {
        return promptBody;
    }
}
