/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.RunnerConfig;

/**
 * Registers service-side external adapters into agent-core runtime extension points.
 *
 * @since 2026-06-24
 */
public interface ExternalSvcAdapterRegistrar {
    /**
     * Registers external service adapters into the given runner config.
     *
     * @param runnerConfig runner config to mutate
     */
    void registerTo(RunnerConfig runnerConfig);

    /**
     * Registers external service adapters into the global runner config.
     */
    void registerToRunner();

    /**
     * Returns a registrar that performs no registration.
     *
     * @return no-op external service adapter registrar
     */
    static ExternalSvcAdapterRegistrar noop() {
        return new ExternalSvcAdapterRegistrar() {
            @Override
            public void registerTo(RunnerConfig runnerConfig) {
            }

            @Override
            public void registerToRunner() {
            }
        };
    }
}
