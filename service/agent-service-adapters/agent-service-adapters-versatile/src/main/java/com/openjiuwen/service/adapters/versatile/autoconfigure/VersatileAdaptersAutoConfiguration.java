/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import com.openjiuwen.service.adapters.versatile.external.VersatileHttpClient;
import com.openjiuwen.service.adapters.versatile.VersatileAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(VersatileProperties.class)
public class VersatileAdaptersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VersatileHttpClient.class)
    public VersatileHttpClient versatileHttpClient(VersatileProperties properties) {
        return new VersatileHttpClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnExpression("'${openjiuwen.service.handler:}' == 'versatile' "
            + "&& '${openjiuwen.service.versatile.base-url:}' != ''")
    public AgentHandler versatileAgentHandler(VersatileHttpClient httpClient, VersatileProperties properties) {
        return new VersatileAgentHandler(httpClient, properties);
    }
}
