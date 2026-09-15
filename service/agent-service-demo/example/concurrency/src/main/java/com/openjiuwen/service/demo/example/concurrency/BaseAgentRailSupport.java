/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;

import java.util.Collection;
import java.util.List;

/**
 * Shared base for concurrency-demo rails: registers the declared tool cards on
 * the owning agent so the model can see them, since harness rails no longer
 * carry their own tool-card list.
 *
 * @since 0.1.2
 */
public abstract class BaseAgentRailSupport extends BaseInterruptRail {
    private final List<ToolCard> toolCards;

    protected BaseAgentRailSupport(Collection<String> toolNames, List<ToolCard> toolCards) {
        super(toolNames);
        this.toolCards = toolCards == null ? List.of() : List.copyOf(toolCards);
    }

    @Override
    public void init(Object agent) {
        super.init(agent);
        if (agent instanceof BaseAgent baseAgent) {
            for (ToolCard card : toolCards) {
                baseAgent.getAbilityManager().add(card);
            }
        }
    }
}
