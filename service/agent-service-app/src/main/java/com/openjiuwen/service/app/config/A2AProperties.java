/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code openjiuwen.service.a2a.*} for AgentCard content, remote agents, JSON-RPC endpoints, and task
 * configuration.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.a2a")
@Data
public class A2AProperties {

    // ======================== Service URL ========================

    /**
     * Public base URL for AgentCard {@code supportedInterfaces[].url}; auto-detected from HTTP request if blank.
     */
    private String publicUrl;

    // ======================== Agent Card fields ========================

    private String agentName = "Agent Runtime";
    private String agentDescription = "OpenJiuwen Agent Runtime Service";
    private String agentVersion = "0.1.0";
    private String documentationUrl;
    private String iconUrl;

    // ======================== Capabilities ========================

    private boolean streaming = true;
    private boolean pushNotifications = false;
    private boolean extendedAgentCard = false;

    // ======================== I/O Modes ========================

    private List<String> defaultInputModes = List.of("text", "text/plain");
    private List<String> defaultOutputModes = List.of("text", "text/plain");

    // ======================== Provider ========================

    private String providerOrganization = "OpenJiuwen";
    private String providerUrl = "https://gitcode.com/openJiuwen";

    // ======================== Skills ========================

    private List<SkillProperties> skills = List.of();

    // ======================== Remote Agents ========================

    private List<RemoteAgentProperties> remoteAgents = List.of();

    // ======================== Paths ========================

    private String jsonRpcPath = "/a2a";
    private String agentCardPath = "/a2a/.well-known/agent-card.json";

    // ======================== Timeout ========================

    private int taskCompletionTimeoutSeconds = 300;

    // ======================== Inner classes ========================

    @Data
    public static class SkillProperties {
        private String id;
        private String name;
        private String description;
        private List<String> tags = List.of();
        private List<String> examples = List.of();
        private List<String> inputModes = List.of("text");
        private List<String> outputModes = List.of("text");
    }

    @Data
    public static class RemoteAgentProperties {
        private String name;
        private String url;
        private int timeoutSeconds = 300;
    }
}
