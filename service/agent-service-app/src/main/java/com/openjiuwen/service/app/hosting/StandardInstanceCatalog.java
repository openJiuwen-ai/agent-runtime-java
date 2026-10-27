/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.app.controller.a2a.StandardAgentCardFactory;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.List;

/**
 * Standard single-process {@link InstanceCatalog}: exposes exactly one
 * instance — the standard agent card with its JSON-RPC endpoint path. The
 * card is built with an empty request base URL, so the registered address
 * relies on the configured public URL (the registration side validates its
 * presence before enabling direct-mode registration).
 *
 * @since 0.1.4
 */
public class StandardInstanceCatalog implements InstanceCatalog {
    private final StandardAgentCardFactory cardFactory;

    private final AgentServiceIdentity identity;

    private final String jsonRpcPath;

    /**
     * Constructs the standard instance catalog.
     *
     * @param cardFactory the standard card factory
     * @param identity the agent service identity
     * @param jsonRpcPath the JSON-RPC endpoint path of the process
     */
    public StandardInstanceCatalog(StandardAgentCardFactory cardFactory, AgentServiceIdentity identity,
            String jsonRpcPath) {
        this.cardFactory = cardFactory;
        this.identity = identity;
        this.jsonRpcPath = jsonRpcPath;
    }

    @Override
    public List<InstanceDescriptor> instances() {
        AgentCard card = cardFactory.card("");
        return List.of(new InstanceDescriptor(identity.getAppName(), jsonRpcPath, card));
    }
}
