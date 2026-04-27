package com.openjiuwen.a2a_service.agents.EDPAgent.config;

public class ScopeConfig {

    private String allowed;
    private String outOfScopeMessage = "尚在学习中";

    public String getAllowed() {
        return allowed;
    }

    public void setAllowed(String allowed) {
        this.allowed = allowed;
    }

    public String getOutOfScopeMessage() {
        return outOfScopeMessage;
    }

    public void setOutOfScopeMessage(String outOfScopeMessage) {
        this.outOfScopeMessage = outOfScopeMessage;
    }
}
