package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleConfig;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;

import java.util.Map;

public class IterationLimitRail extends AgentRail {

    private final int maxIterations;

    public IterationLimitRail(AgentRuleConfig config) {
        this.maxIterations = config.getLimits().getMaxIterations();
        setPriority(40);
    }

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        int currentCount = asInt(ctx.getSession().getState(StateKeys.ITER_COUNT));
        int newCount = currentCount + 1;
        ctx.getSession().updateState(Map.of(StateKeys.ITER_COUNT, newCount));

        if (newCount >= maxIterations) {
            ctx.requestForceFinish(Map.of(
                    "type", "iteration_limit_exceeded",
                    "content", "已达到最大迭代次数限制(" + maxIterations + ")，终止执行",
                    "iteration_count", newCount
            ));
        }
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
