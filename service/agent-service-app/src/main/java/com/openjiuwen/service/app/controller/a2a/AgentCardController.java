/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the A2A-standard Agent Card on multiple well-known paths. All fields are driven by {@link A2AProperties}
 * configuration.
 *
 * @since 0.1.0
 */
@RestController
public class AgentCardController {

    private final A2AProperties a2aProperties;
    private final AgentServiceIdentity identity;
    private final ServiceProperties serviceProperties;

    /**
     * Constructs the agent card controller.
     *
     * @param a2aProperties the A2A configuration properties
     * @param identity the agent service identity
     * @param serviceProperties the service properties
     */
    public AgentCardController(A2AProperties a2aProperties, AgentServiceIdentity identity,
            ServiceProperties serviceProperties) {
        this.a2aProperties = a2aProperties;
        this.identity = identity;
        this.serviceProperties = serviceProperties;
    }

    /**
     * Returns the agent card on the standard well-known path.
     *
     * @param request the HTTP servlet request
     * @return the agent card
     */
    @GetMapping(A2AServicePaths.WELL_KNOWN_AGENT_CARD)
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
    public AgentCard getPrefixedCard(HttpServletRequest request) {
        return buildCard(request);
    }

    private AgentCard buildCard(HttpServletRequest request) {
        String baseUrl = (a2aProperties.getPublicUrl() != null && !a2aProperties.getPublicUrl().isBlank())
                ? a2aProperties.getPublicUrl()
                : request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();

        String jsonRpcUrl = baseUrl.replaceAll("/$", "") + a2aProperties.getJsonRpcPath();

        List<AgentSkill> skills = a2aProperties.getSkills().stream().map(s -> new AgentSkill(s.getId(), s.getName(),
                s.getDescription(), s.getTags(), s.getExamples(), s.getInputModes(), s.getOutputModes(), List.of()))
                .toList();

        return new AgentCard(identity.getAppName(), a2aProperties.getAgentDescription(),
                new AgentProvider(a2aProperties.getProviderOrganization(), a2aProperties.getProviderUrl()),
                serviceProperties.getVersion(), a2aProperties.getDocumentationUrl(),
                new AgentCapabilities(a2aProperties.isStreaming(), a2aProperties.isPushNotifications(),
                        a2aProperties.isExtendedAgentCard(), List.of()),
                a2aProperties.getDefaultInputModes(), a2aProperties.getDefaultOutputModes(), skills, Map.of(),
                List.of(),
                a2aProperties.getIconUrl(),
                List.of(new AgentInterface("JSONRPC", jsonRpcUrl, null, "1.0")),
                List.of(),
                jsonRpcUrl,
                "JSONRPC",
                List.of());
    }
}
