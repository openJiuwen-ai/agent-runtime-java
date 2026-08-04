/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code openjiuwen.service.a2a.*} for AgentCard content, remote agents,
 * JSON-RPC endpoints, and task
 * configuration.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.a2a")
@Data
public class A2AProperties {
    /**
     * Public base URL for AgentCard {@code supportedInterfaces[].url};
     * auto-detected from HTTP request if blank.
     */
    private String publicUrl;

    private String agentDescription = "OpenJiuwen Agent Runtime Service";

    private String documentationUrl;

    private String iconUrl;

    private boolean isStreaming = true;

    private boolean isPushNotifications = false;

    private boolean isExtendedAgentCard = false;

    private List<String> defaultInputModes = List.of("text", "text/plain");

    private List<String> defaultOutputModes = List.of("text", "text/plain");

    private String providerOrganization = "";

    private String providerUrl = "";

    private List<SkillProperties> skills = List.of();

    private List<RemoteAgentProperties> remoteAgents = List.of();

    private String jsonRpcPath = "/a2a";

    private int taskCompletionTimeoutSeconds = 300;

    private RemoteInvocationProperties remoteInvocation = new RemoteInvocationProperties();

    /** Runtime-level bounded remote invocation configuration. */
    @Data
    public static class RemoteInvocationProperties {
        private int maxConcurrency = 16;

        private int maxQueueSize = 256;

        private long queueTimeoutSeconds = 30L;
    }

    /**
     * Skill definition for the AgentCard.
     */
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

    /**
     * Remote agent discovery configuration.
     */
    @Data
    public static class RemoteAgentProperties {
        private String name;

        private String url;

        private int timeoutSeconds = 300;

        private boolean isStreaming = false;
    }
}
