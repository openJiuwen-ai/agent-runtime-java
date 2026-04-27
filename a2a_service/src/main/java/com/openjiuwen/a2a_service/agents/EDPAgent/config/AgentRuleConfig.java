package com.openjiuwen.a2a_service.agents.EDPAgent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentRuleConfig {

    private ScopeConfig scope = new ScopeConfig();
    private List<String> planningSteps = new ArrayList<String>();
    private LimitsConfig limits = new LimitsConfig();
    private SummaryConfig summary = new SummaryConfig();
    private Map<String, String> scripts = new LinkedHashMap<String, String>();

    public ScopeConfig getScope() {
        return scope;
    }

    public void setScope(ScopeConfig scope) {
        this.scope = scope != null ? scope : new ScopeConfig();
    }

    public List<String> getPlanningSteps() {
        return planningSteps;
    }

    public void setPlanningSteps(List<String> planningSteps) {
        this.planningSteps = planningSteps != null ? new ArrayList<String>(planningSteps) : new ArrayList<String>();
    }

    public LimitsConfig getLimits() {
        return limits;
    }

    public void setLimits(LimitsConfig limits) {
        this.limits = limits != null ? limits : new LimitsConfig();
    }

    public SummaryConfig getSummary() {
        return summary;
    }

    public void setSummary(SummaryConfig summary) {
        this.summary = summary != null ? summary : new SummaryConfig();
    }

    public Map<String, String> getScripts() {
        return scripts;
    }

    public void setScripts(Map<String, String> scripts) {
        this.scripts = scripts != null ? new LinkedHashMap<String, String>(scripts) : new LinkedHashMap<String, String>();
    }
}
