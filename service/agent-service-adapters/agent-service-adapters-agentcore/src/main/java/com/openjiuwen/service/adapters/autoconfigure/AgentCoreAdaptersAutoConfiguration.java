/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.autoconfigure;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AgentCoreAdaptersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service", name = "agent-id")
    public AgentHandler coreAgentHandler(@Value("${openjiuwen.service.agent-id}") String agentId) {
        return new CoreAgentHandler(agentId);
    }
}
