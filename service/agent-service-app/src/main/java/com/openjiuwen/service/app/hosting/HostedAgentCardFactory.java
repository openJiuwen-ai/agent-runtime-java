/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.HostedCardProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.paths.A2AServicePaths;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes Card content at startup and supplies an address view per HTTP request.
 * This factory never inspects an underlying Core Agent or uses Card names to route.
 *
 * @since 0.1.2
 */
public final class HostedAgentCardFactory {
    private final Map<String, AgentCard> cards;

    private final String publicBaseUrl;

    /**
     * Validates Card associations and freezes their effective content.
     *
     * @param definitions registered execution targets
     * @param properties existing global policy plus Card-only overrides
     * @param serviceProperties existing service version
     */
    public HostedAgentCardFactory(HostedAgentDefinitions definitions, A2AProperties properties,
            ServiceProperties serviceProperties) {
        if (!"/a2a".equals(properties.getJsonRpcPath()) && !"/a2a/".equals(properties.getJsonRpcPath())) {
            throw new IllegalArgumentException("Hosted a2a.json-rpc-path must be /a2a");
        }
        publicBaseUrl = normalizePublicUrl(properties.getPublicUrl());
        Map<String, AgentCard> built = new LinkedHashMap<>();
        for (var entry : definitions.entries()) {
            HostedCardProperties overrides = properties.getAgents().getOrDefault(entry.agentId(),
                    new HostedCardProperties());
            built.put(entry.agentId(), build(entry.agentId(), overrides, properties,
                    serviceProperties.getVersion()));
        }
        for (String configured : properties.getAgents().keySet()) {
            if (!built.containsKey(configured)) {
                throw new IllegalArgumentException("Card configuration references an unregistered agent: " + configured);
            }
        }
        cards = Map.copyOf(built);
    }

    /**
     * Creates a root or named address view without caching request host information.
     *
     * @param agentId already selected registered identifier
     * @param requestBaseUrl current HTTP request origin and servlet context path
     * @param isDefaultEntry whether to advertise the legacy root RPC path
     * @return the SDK Card for this address
     */
    public AgentCard card(String agentId, String requestBaseUrl, boolean isDefaultEntry) {
        AgentCard content = cards.get(agentId);
        if (content == null) {
            throw new IllegalArgumentException("Unknown hosted agent");
        }
        String base = publicBaseUrl.isEmpty() ? trimTrailingSlash(requestBaseUrl) : publicBaseUrl;
        String path = isDefaultEntry ? A2AServicePaths.A2A_JSONRPC_NO_SLASH
                : A2AServicePaths.HOSTED_AGENTS + "/" + agentId;
        String url = base + path;
        return AgentCard.builder(content).url(url)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0"))).build();
    }

    /**
     * Returns a trusted callback URL only for calls that already selected push mode.
     *
     * @param agentId originating registered identifier
     * @return configured public callback address
     */
    public String callbackUrl(String agentId) {
        if (!cards.containsKey(agentId)) {
            throw new IllegalArgumentException("Unknown hosted agent");
        }
        if (publicBaseUrl.isEmpty()) {
            throw new IllegalStateException("Hosted push invocation requires openjiuwen.service.a2a.public-url");
        }
        return publicBaseUrl + A2AServicePaths.A2A_PUSH_NOTIFICATION_CALLBACK + "/" + agentId;
    }

    private static AgentCard build(String agentId, HostedCardProperties overrides, A2AProperties global,
            String serviceVersion) {
        String name = value(overrides.getAgentName(), agentId);
        if (name.isBlank()) {
            throw new IllegalArgumentException("a2a.agents." + agentId + ".agent-name must not be blank");
        }
        var skills = overrides.getSkills().stream().map(HostedAgentCardFactory::skill).toList();
        return new AgentCard(name, value(overrides.getAgentDescription(), ""),
                new AgentProvider(value(overrides.getProviderOrganization(), value(global.getProviderOrganization(), "")),
                        value(overrides.getProviderUrl(), value(global.getProviderUrl(), ""))),
                value(overrides.getVersion(), serviceVersion),
                value(overrides.getDocumentationUrl(), global.getDocumentationUrl()),
                new AgentCapabilities(global.isStreaming() && !Boolean.FALSE.equals(overrides.getStreaming()),
                        global.isPushNotifications()
                                && !Boolean.FALSE.equals(overrides.getPushNotifications()),
                        global.isExtendedAgentCard(), List.of()),
                modes(overrides.getDefaultInputModes(), global.getDefaultInputModes()),
                modes(overrides.getDefaultOutputModes(), global.getDefaultOutputModes()),
                skills, Map.of(), List.of(), value(overrides.getIconUrl(), global.getIconUrl()),
                List.of(), List.of(), null, "JSONRPC", List.of());
    }

    private static AgentSkill skill(A2AProperties.SkillProperties value) {
        return new AgentSkill(value.getId(), value.getName(), value.getDescription(), List.copyOf(value.getTags()),
                List.copyOf(value.getExamples()), List.copyOf(value.getInputModes()), List.copyOf(value.getOutputModes()),
                List.of());
    }

    private static List<String> modes(List<String> override, List<String> defaults) {
        List<String> selected = override == null ? defaults : override;
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException("Hosted Card content modes must not be empty");
        }
        for (String mode : selected) {
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("Hosted Card content mode must not be blank");
            }
            if (!"text".equals(mode)) {
                MediaType.parseMediaType(mode);
            }
        }
        return List.copyOf(selected);
    }

    private static String value(String configured, String fallback) {
        return configured == null ? fallback : configured;
    }

    private static String normalizePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Hosted a2a.public-url must be an HTTP(S) base URL");
        }
        return trimTrailingSlash(value);
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
