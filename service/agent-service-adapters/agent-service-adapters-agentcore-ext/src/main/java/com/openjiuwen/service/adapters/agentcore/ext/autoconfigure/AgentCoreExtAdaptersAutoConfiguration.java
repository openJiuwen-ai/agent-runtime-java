/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreExtAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AgentCoreExtAdaptersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnExpression("'${openjiuwen.service.agent-id:}' != '' "
            + "&& '${openjiuwen.service.handler:}' == 'agentcore-ext'")
    public AgentHandler coreAgentExtHandler(@Value("${openjiuwen.service.agent-id}") String agentId) {
        return new JiuwenCoreExtAgentHandler(agentId);
    }
}
