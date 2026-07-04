/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.support.remote;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.singleagent.schema.AgentResult;
import com.openjiuwen.core.singleagent.schema.Artifact;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Internal test helper: minimal A2A remote client creation and invoke sample.
 *
 * @since 2026-07-03
 */
public final class A2ARemoteAdapterExample {
    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAdapterExample.class);

    private A2ARemoteAdapterExample() {
    }

    public static void main(String[] args) throws Exception {
        String remoteUrl = option(args, "--url=", "http://localhost:18082/a2a");
        String clientId = option(args, "--client-id=", "demo-a2a-remote");
        String operation = option(args, "--operation=", "create");

        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getRemote().setTimeoutMs(intOption(args, "--timeout-ms=", 3000));
        properties.getRemote().setRetryInvoke(isOptionEnabled(args, "--retry-invoke=", false));
        properties.getRemote().getRetry().setMax(intOption(args, "--retry-max=", 1));
        properties.getRemote().getRetry().setBackoffMs(intOption(args, "--retry-backoff-ms=", 200));
        properties.getRemote().getCircuitBreaker().setEnabled(true);
        properties.getRemote().getCircuitBreaker().setFailureThreshold(3);
        properties.getRemote().getCircuitBreaker().setResetTimeoutMs(30000);

        AgentCoreExternalProperties.RemoteClientEndpoint remoteClient
            = new AgentCoreExternalProperties.RemoteClientEndpoint();
        remoteClient.setId(clientId);
        remoteClient.setName(option(args, "--client-name=", "Demo A2A Remote"));
        remoteClient.setProtocol("A2A");
        remoteClient.setUrl(remoteUrl);
        properties.getRemote().setClients(List.of(remoteClient));

        AgentCoreRemoteClientFactory factory = new DefaultAgentCoreRemoteClientFactory(properties,
            new DefaultAgentCoreRemoteClientDecoratorFactory());
        RemoteClient client = factory.create(clientId);

        log.info("A2A Remote URL: {}", remoteUrl);
        log.info("Created client: {}", client.getClass().getName());
        runOperation(args, operation, client);
    }

    private static void runOperation(String[] args, String operation, RemoteClient client) throws Exception {
        if (!"invoke".equals(operation)) {
            log.info("No remote operation executed. Use --operation=invoke.");
            return;
        }
        String message = option(args, "--message=", "hello remote");
        String conversationId = option(args, "--conversation-id=", "demo-session");
        Object result = client.invoke(Map.of("message", message, "conversation_id", conversationId), null);
        logAgentResult(result);
    }

    private static void logAgentResult(Object result) {
        if (!(result instanceof AgentResult agentResult)) {
            log.info("remote result: {}", result);
            return;
        }
        log.info("remote result status: {}", agentResult.getStatus());
        log.info("remote result session: {}", agentResult.getSessionId());
        log.info("remote result text: {}", firstText(agentResult.getArtifacts()));
    }

    private static String firstText(List<Artifact> artifacts) {
        if (artifacts == null) {
            return "";
        }
        for (Artifact artifact : artifacts) {
            if (artifact == null || artifact.getParts() == null) {
                continue;
            }
            for (com.openjiuwen.core.common.schema.Part part : artifact.getParts()) {
                if (part != null && part.getContent() != null) {
                    return part.getContent();
                }
            }
        }
        return "";
    }

    private static String option(String[] args, String prefix, String defaultValue) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return defaultValue;
    }

    private static int intOption(String[] args, String prefix, int defaultValue) {
        String value = option(args, prefix, String.valueOf(defaultValue));
        return Integer.parseInt(value);
    }

    private static boolean isOptionEnabled(String[] args, String prefix, boolean shouldUseDefault) {
        String value = option(args, prefix, String.valueOf(shouldUseDefault));
        return Boolean.parseBoolean(value);
    }
}
