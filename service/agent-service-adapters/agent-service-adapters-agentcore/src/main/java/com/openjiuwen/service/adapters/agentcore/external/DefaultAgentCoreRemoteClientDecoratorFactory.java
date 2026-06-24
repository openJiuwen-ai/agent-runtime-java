/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;

/**
 * Default factory for decorating Core remote clients with Service policies.
 *
 * @since 2026-06-24
 */
public class DefaultAgentCoreRemoteClientDecoratorFactory implements AgentCoreRemoteClientDecoratorFactory {
    @Override
    public RemoteClient decorate(
            RemoteClientConfig config,
            RemoteClient delegate,
            AgentCoreExternalProperties.RemotePolicy policy) {
        return new DecoratingRemoteClient(config, delegate, policy);
    }
}
