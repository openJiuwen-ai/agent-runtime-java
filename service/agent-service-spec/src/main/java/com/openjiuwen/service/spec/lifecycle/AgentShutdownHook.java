/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Hook invoked during the AgentApp shutdown phase (before the context closes).
 */
public interface AgentShutdownHook {

    void onShutdown(AgentLifecycleContext context);
}
