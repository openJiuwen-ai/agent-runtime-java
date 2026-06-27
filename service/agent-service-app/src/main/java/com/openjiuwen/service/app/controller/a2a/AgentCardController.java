/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.spec.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves the A2A-standard Agent Card on multiple well-known paths.
 * All fields are driven by {@link A2AProperties} configuration.
 *
 * @since 0.1.0
 */
@RestController
public class AgentCardController {

    private final A2AProperties properties;

    public AgentCardController(A2AProperties properties) {
        this.properties = properties;
    }

    @GetMapping(A2AServicePaths.WELL_KNOWN_AGENT_CARD)
    public AgentCard getStandardCard(HttpServletRequest request) {
        return buildCard(request);
    }

    @GetMapping(A2AServicePaths.WELL_KNOWN_AGENT_JSON)
    public AgentCard getCompatCard(HttpServletRequest request) {
        return buildCard(request);
    }

    @GetMapping(A2AServicePaths.A2A_WELL_KNOWN_CARD)
    public AgentCard getPrefixedCard(HttpServletRequest request) {
        return buildCard(request);
    }

    private AgentCard buildCard(HttpServletRequest request) {
        String baseUrl = (properties.getPublicUrl() != null && !properties.getPublicUrl().isBlank())
                ? properties.getPublicUrl()
                : request.getScheme() + "://" + request.getServerName() + ":"
                  + request.getServerPort();

        String jsonRpcUrl = baseUrl.replaceAll("/$", "") + properties.getJsonRpcPath();

        List<AgentSkill> skills = properties.getSkills().stream()
                .map(s -> new AgentSkill(s.getId(), s.getName(), s.getDescription(),
                        s.getTags(), s.getExamples(), s.getInputModes(), s.getOutputModes(),
                        List.of()))
                .toList();

        return new AgentCard(
                properties.getAgentName(),
                properties.getAgentDescription(),
                new AgentProvider(properties.getProviderOrganization(),
                                  properties.getProviderUrl()),
                properties.getAgentVersion(),
                properties.getDocumentationUrl(),
                new AgentCapabilities(properties.isStreaming(),
                        properties.isPushNotifications(),
                        properties.isExtendedAgentCard(),
                        List.of()),
                properties.getDefaultInputModes(),
                properties.getDefaultOutputModes(),
                skills,
                Map.of(),   // securitySchemes (P0)
                List.of(),  // securityRequirements (P0)
                properties.getIconUrl(),
                List.of(new AgentInterface("JSONRPC", jsonRpcUrl, null, "1.0")),
                List.of(),  // signatures
                jsonRpcUrl, // url = jsonRpcUrl (legacy compatibility)
                "JSONRPC",  // preferredTransport
                List.of()   // additionalInterfaces
        );
    }
}
