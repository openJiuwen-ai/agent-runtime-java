/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AgentCoreAdaptersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnExpression("'${openjiuwen.service.agent-id:}' != '' "
            + "&& '${openjiuwen.service.handler:agentcore}' == 'agentcore'")
    public AgentHandler coreAgentHandler(@Value("${openjiuwen.service.agent-id}") String agentId) {
        return new JiuwenCoreAgentHandler(agentId);
    }
}
