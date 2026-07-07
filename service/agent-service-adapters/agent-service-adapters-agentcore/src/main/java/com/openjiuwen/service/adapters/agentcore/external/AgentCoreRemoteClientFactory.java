/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;

/**
 * Factory for creating Core remote clients from Service external configuration.
 *
 * @since 2026-06-24
 */
public interface AgentCoreRemoteClientFactory {
    /**
     * Creates the first configured remote client.
     *
     * @return configured remote client
     */
    RemoteClient create();

    /**
     * Creates the configured remote client with the given id.
     *
     * @param clientId remote client id
     * @return configured remote client
     */
    RemoteClient create(String clientId);

    /**
     * Maps a configured remote client to the Core config object.
     *
     * @param clientId remote client id
     * @return Core remote client config
     */
    RemoteClientConfig configFor(String clientId);
}
