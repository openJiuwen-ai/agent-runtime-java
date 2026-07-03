/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import com.openjiuwen.core.runner.RunnerConfig;

/**
 * Applies middleware adapter configuration to Core {@link RunnerConfig} before
 * {@code Runner.start()}.
 *
 * @since 0.1.0
 */
public interface MiddlewareAdapterRegistrar {

    /**
     * Optional override/extension of Core Factory providers. P0 relies on
     * ServiceLoader defaults.
     */
    default void registerFactories() {
    }

    /**
     * Populates checkpointer (and P2 placeholder fields) on the global runner
     * configuration.
     */
    void applyToRunnerConfig(RunnerConfig runnerConfig);
}
