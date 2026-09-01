/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import java.util.Map;

/**
 * Default registrar: writes {@link RunnerConfig#getCheckpointerConfig()} from
 * service properties.
 *
 * @since 0.1.0
 */
public class DefaultMiddlewareAdapterRegistrar implements MiddlewareAdapterRegistrar {
    private final MiddlewareProperties middlewareProperties;

    private final CredentialDecryptor credentialDecryptor;

    private final RuntimeRedisClient runtimeRedisClient;

    public DefaultMiddlewareAdapterRegistrar(MiddlewareProperties middlewareProperties,
            CredentialDecryptor credentialDecryptor, RuntimeRedisClient runtimeRedisClient) {
        this.middlewareProperties = middlewareProperties;
        this.credentialDecryptor = credentialDecryptor;
        this.runtimeRedisClient = runtimeRedisClient;
    }

    @Override
    public void applyToRunnerConfig(RunnerConfig runnerConfig) {
        Map<String, Object> checkpointerConfig = AgentCoreCheckpointerConfigAssembler.build(middlewareProperties,
                credentialDecryptor, runtimeRedisClient);
        runnerConfig.setCheckpointerConfig(checkpointerConfig);
    }
}
