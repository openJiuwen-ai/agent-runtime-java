/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.spec.paths.A2AServicePaths;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosted-mode {@link InstanceCatalog}: exposes every published hosted
 * registration, each serving its per-agent JSON-RPC endpoint path. Cards are
 * projected per agent from the hosted card factory with an empty request base
 * URL, so the registered address relies on the configured public URL (the
 * registration side validates its presence before enabling direct-mode
 * registration). Reading the instances fails while the hosted catalog is not
 * yet published — the registration side treats that as a retriable
 * not-ready condition.
 *
 * @since 0.1.4
 */
public class HostedInstanceCatalog implements InstanceCatalog {
    private final HostedRuntimeCatalog catalog;

    private final HostedAgentCardFactory cardFactory;

    /**
     * Constructs the hosted instance catalog.
     *
     * @param catalog the published hosted runtime catalog
     * @param cardFactory the hosted card factory
     */
    public HostedInstanceCatalog(HostedRuntimeCatalog catalog, HostedAgentCardFactory cardFactory) {
        this.catalog = catalog;
        this.cardFactory = cardFactory;
    }

    @Override
    public List<InstanceDescriptor> instances() {
        List<InstanceDescriptor> descriptors = new ArrayList<>();
        for (var runtime : catalog.instances()) {
            descriptors.add(new InstanceDescriptor(runtime.agentId(), instancePath(runtime.agentId()),
                    cardFactory.card(runtime.agentId(), "", false)));
        }
        return descriptors;
    }

    private static String instancePath(String agentId) {
        return A2AServicePaths.HOSTED_AGENTS + "/" + agentId;
    }
}
