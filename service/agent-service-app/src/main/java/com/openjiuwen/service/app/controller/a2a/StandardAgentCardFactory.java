/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;

import java.util.List;
import java.util.Map;

/**
 * Assembles the standard single-process A2A agent card. Shared by the
 * well-known card endpoint and the standard instance catalog, so the served
 * card and the registered card are always the same fact.
 *
 * @since 0.1.4
 */
public class StandardAgentCardFactory {

    private final A2AProperties a2aProperties;

    private final AgentServiceIdentity identity;

    private final ServiceProperties serviceProperties;

    private final A2aPushNotificationCapabilityGate pushNotificationCapabilityGate;

    /**
     * Constructs the factory.
     *
     * @param a2aProperties the A2A configuration properties
     * @param identity the agent service identity
     * @param serviceProperties the service properties
     * @param pushNotificationCapabilityGate the push notification capability gate
     */
    public StandardAgentCardFactory(A2AProperties a2aProperties, AgentServiceIdentity identity,
            ServiceProperties serviceProperties, A2aPushNotificationCapabilityGate pushNotificationCapabilityGate) {
        this.a2aProperties = a2aProperties;
        this.identity = identity;
        this.serviceProperties = serviceProperties;
        this.pushNotificationCapabilityGate = pushNotificationCapabilityGate;
    }

    /**
     * Builds the standard agent card; {@code public-url} wins over the request
     * base URL, matching the well-known card endpoint behavior.
     *
     * @param requestBaseUrl request-derived base URL, used when public-url is unset
     * @return the standard agent card
     */
    public AgentCard card(String requestBaseUrl) {
        String baseUrl = (a2aProperties.getPublicUrl() != null && !a2aProperties.getPublicUrl().isBlank())
            ? a2aProperties.getPublicUrl()
            : requestBaseUrl;

        String jsonRpcUrl = baseUrl.replaceAll("/$", "") + a2aProperties.getJsonRpcPath();

        List<AgentSkill> skills = a2aProperties.getSkills()
            .stream()
            .map(s -> new AgentSkill(s.getId(), s.getName(), s.getDescription(), s.getTags(), s.getExamples(),
                s.getInputModes(), s.getOutputModes(), List.of()))
            .toList();

        String providerOrg = a2aProperties.getProviderOrganization() != null
            ? a2aProperties.getProviderOrganization()
            : "";
        String providerUrl = a2aProperties.getProviderUrl() != null ? a2aProperties.getProviderUrl() : "";

        return new AgentCard(identity.getAppName(), a2aProperties.getAgentDescription(),
            new AgentProvider(providerOrg, providerUrl), serviceProperties.getVersion(),
            a2aProperties.getDocumentationUrl(),
            new AgentCapabilities(a2aProperties.isStreaming(),
                pushNotificationCapabilityGate.isPushNotificationsEnabled(), a2aProperties.isExtendedAgentCard(),
                List.of()), a2aProperties.getDefaultInputModes(),
            a2aProperties.getDefaultOutputModes(), skills, Map.of(), List.of(), a2aProperties.getIconUrl(),
            List.of(new AgentInterface("JSONRPC", jsonRpcUrl, null, "1.0")), List.of(), jsonRpcUrl, "JSONRPC",
            List.of());
    }
}
