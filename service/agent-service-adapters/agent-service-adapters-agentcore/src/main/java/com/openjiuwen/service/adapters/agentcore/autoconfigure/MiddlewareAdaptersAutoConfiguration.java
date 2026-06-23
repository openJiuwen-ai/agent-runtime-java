/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = AgentCoreAdaptersAutoConfiguration.class)
@ConditionalOnClass(RunnerConfig.class)
@EnableConfigurationProperties(MiddlewareProperties.class)
public class MiddlewareAdaptersAutoConfiguration {

    @Bean
    public MiddlewareAdapterRegistrar middlewareAdapterRegistrar(MiddlewareProperties middlewareProperties,
                                                                 CredentialDecryptor credentialDecryptor) {
        DefaultMiddlewareAdapterRegistrar registrar =
                new DefaultMiddlewareAdapterRegistrar(middlewareProperties, credentialDecryptor);
        registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        return registrar;
    }
}
