package com.openjiuwen.a2a_service.agents.EDPAgent.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class InterruptResolveRequest {

    @JsonProperty("interrupt_id")
    @JsonAlias("interruptId")
    private String interruptId = "";
    @JsonProperty("user_input")
    @JsonAlias("userInput")
    private String userInput = "";
    private String action = "resume";

    public String getInterruptId() {
        return interruptId;
    }

    public void setInterruptId(String interruptId) {
        this.interruptId = interruptId;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
