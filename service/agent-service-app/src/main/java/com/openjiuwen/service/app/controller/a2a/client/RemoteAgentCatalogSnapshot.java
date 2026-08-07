/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import java.util.List;

/**
 * Immutable versioned snapshot of all discovered remote A2A agents.
 *
 * @param version monotonically increasing registry version
 * @param entries complete remote-agent entries sorted by name
 * @since 0.1.1
 */
public record RemoteAgentCatalogSnapshot(long version, List<A2ARemoteAgentCardRegistry.RemoteAgentEntry> entries) {
    /**
     * Creates an immutable snapshot.
     */
    public RemoteAgentCatalogSnapshot {
        entries = List.copyOf(entries);
    }
}
