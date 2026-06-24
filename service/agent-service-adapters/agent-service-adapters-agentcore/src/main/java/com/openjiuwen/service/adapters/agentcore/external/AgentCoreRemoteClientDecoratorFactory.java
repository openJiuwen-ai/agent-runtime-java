/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;

/**
 * Customization point for wrapping Core remote clients with Service egress behavior.
 *
 * @since 2026-06-24
 */
public interface AgentCoreRemoteClientDecoratorFactory {
    /**
     * Wraps a Core remote client with Service external-call policies.
     *
     * @param config remote client configuration
     * @param delegate Core remote client to wrap
     * @param policy remote outbound call policy
     * @return decorated remote client
     */
    RemoteClient decorate(
            RemoteClientConfig config,
            RemoteClient delegate,
            AgentCoreExternalProperties.RemotePolicy policy);
}
