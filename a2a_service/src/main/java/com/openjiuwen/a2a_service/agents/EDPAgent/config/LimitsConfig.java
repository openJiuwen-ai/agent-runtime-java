package com.openjiuwen.a2a_service.agents.EDPAgent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LimitsConfig {

    private int maxIterations = 30;
    private int maxInputAttempts = 3;
    private int interruptTimeoutSeconds = 300;
    private Map<String, Integer> tasks = new LinkedHashMap<String, Integer>();
    private List<String> terminationKeywords = new ArrayList<String>(List.of(
            "终止执行", "取消", "退出", "stop", "cancel"
    ));

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxInputAttempts() {
        return maxInputAttempts;
    }

    public void setMaxInputAttempts(int maxInputAttempts) {
        this.maxInputAttempts = maxInputAttempts;
    }

    public int getInterruptTimeoutSeconds() {
        return interruptTimeoutSeconds;
    }

    public void setInterruptTimeoutSeconds(int interruptTimeoutSeconds) {
        this.interruptTimeoutSeconds = interruptTimeoutSeconds;
    }

    public Map<String, Integer> getTasks() {
        return tasks;
    }

    public void setTasks(Map<String, Integer> tasks) {
        this.tasks = tasks != null ? new LinkedHashMap<String, Integer>(tasks) : new LinkedHashMap<String, Integer>();
    }

    public List<String> getTerminationKeywords() {
        return terminationKeywords;
    }

    public void setTerminationKeywords(List<String> terminationKeywords) {
        this.terminationKeywords = terminationKeywords != null
                ? new ArrayList<String>(terminationKeywords)
                : new ArrayList<String>();
    }
}
