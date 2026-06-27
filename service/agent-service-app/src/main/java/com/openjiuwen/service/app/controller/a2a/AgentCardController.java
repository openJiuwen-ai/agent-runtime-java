/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
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

    private final A2AProperties properties;

    /**
     * Constructs the agent card controller.
     *
     * @param properties the A2A configuration properties
     */
    public AgentCardController(A2AProperties properties) {
        this.properties = properties;
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
        String baseUrl = (properties.getPublicUrl() != null && !properties.getPublicUrl().isBlank())
                ? properties.getPublicUrl()
                : request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();

        String jsonRpcUrl = baseUrl.replaceAll("/$", "") + properties.getJsonRpcPath();

        List<AgentSkill> skills = properties.getSkills().stream().map(s -> new AgentSkill(s.getId(), s.getName(),
                s.getDescription(), s.getTags(), s.getExamples(), s.getInputModes(), s.getOutputModes(), List.of()))
                .toList();

        return new AgentCard(properties.getAgentName(), properties.getAgentDescription(),
                new AgentProvider(properties.getProviderOrganization(), properties.getProviderUrl()),
                properties.getAgentVersion(), properties.getDocumentationUrl(),
                new AgentCapabilities(properties.isStreaming(), properties.isPushNotifications(),
                        properties.isExtendedAgentCard(), List.of()),
                properties.getDefaultInputModes(), properties.getDefaultOutputModes(), skills, Map.of(),
                List.of(),
                properties.getIconUrl(),
                List.of(new AgentInterface("JSONRPC", jsonRpcUrl, null, "1.0")),
                List.of(),
                jsonRpcUrl,
                "JSONRPC",
                List.of());
    }
}
