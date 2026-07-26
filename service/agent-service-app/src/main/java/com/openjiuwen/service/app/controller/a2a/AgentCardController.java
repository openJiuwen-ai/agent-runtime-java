/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves the A2A-standard Agent Card on multiple well-known paths. All fields
 * are driven by {@link A2AProperties}
 * configuration.
 *
 * @since 0.1.0
 */
@RestController
public class AgentCardController {
    private final A2AProperties a2aProperties;

    private final AgentServiceIdentity identity;

    private final ServiceProperties serviceProperties;

    private final A2aPushNotificationCapabilityGate pushNotificationCapabilityGate;

    /**
     * Constructs the agent card controller.
     *
     * @param a2aProperties the A2A configuration properties
     * @param identity the agent service identity
     * @param serviceProperties the service properties
     * @param pushNotificationCapabilityGate the push notification capability gate
     */
    public AgentCardController(A2AProperties a2aProperties, AgentServiceIdentity identity,
        ServiceProperties serviceProperties, A2aPushNotificationCapabilityGate pushNotificationCapabilityGate) {
        this.a2aProperties = a2aProperties;
        this.identity = identity;
        this.serviceProperties = serviceProperties;
        this.pushNotificationCapabilityGate = pushNotificationCapabilityGate;
    }

    /**
     * Returns the agent card on the standard well-known path.
     *
     * @param request the HTTP servlet request
     * @return the agent card
     */
    @GetMapping(A2AServicePaths.WELL_KNOWN_AGENT_CARD)
    @AuthorizedResource(resource = "agent-card", action = "read")
    public AgentCard getStandardCard(HttpServletRequest request) {
        return buildCard(request);
    }

    /**
     * Returns the agent card on the legacy {@code agent.json} path.
     *
     * @param request the HTTP servlet request
     * @return the agent card
     */
    @GetMapping(A2AServicePaths.WELL_KNOWN_AGENT_JSON)
    @AuthorizedResource(resource = "agent-card", action = "read")
    public AgentCard getCompatCard(HttpServletRequest request) {
        return buildCard(request);
    }

    /**
     * Returns the agent card on the prefixed A2A well-known path.
     *
     * @param request the HTTP servlet request
     * @return the agent card
     */
    @GetMapping(A2AServicePaths.A2A_WELL_KNOWN_CARD)
    @AuthorizedResource(resource = "agent-card", action = "read")
    public AgentCard getPrefixedCard(HttpServletRequest request) {
        return buildCard(request);
    }

    private AgentCard buildCard(HttpServletRequest request) {
        String baseUrl = (a2aProperties.getPublicUrl() != null && !a2aProperties.getPublicUrl().isBlank())
            ? a2aProperties.getPublicUrl()
            : request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();

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
