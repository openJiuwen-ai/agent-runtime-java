/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.sandbox;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreSandboxClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Minimal example showing how Service external adapters configure and create Core sandbox clients.
 *
 * @since 2026-06-24
 */
public final class SandboxAdapterExample {
    private static final Logger log = LoggerFactory.getLogger(SandboxAdapterExample.class);

    private SandboxAdapterExample() {
    }

    public static void main(String[] args) {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setTimeoutMs(intOption(args, "--timeout-ms=", 3000));
        properties.getSandbox().getRetry().setMax(intOption(args, "--retry-max=", 1));
        properties.getSandbox().getRetry().setBackoffMs(intOption(args, "--retry-backoff-ms=", 200));
        properties.getSandbox().getCircuitBreaker().setEnabled(true);
        properties.getSandbox().getCircuitBreaker().setFailureThreshold(3);
        properties.getSandbox().getCircuitBreaker().setResetTimeoutMs(30000);

        String serverId = option(args, "--server-id=", "default");
        String serviceUrl = option(args, "--url=", "http://localhost:18090");
        AgentCoreExternalProperties.SandboxServer server = new AgentCoreExternalProperties.SandboxServer();
        server.setServerId(serverId);
        server.setServiceUrl(serviceUrl);
        server.setSandboxType(option(args, "--sandbox-type=", "jiuwenbox"));
        server.setLauncherType(option(args, "--launcher-type=", "pre_deploy"));
        server.setRootPath(option(args, "--root-path=", "."));
        properties.getSandbox().setServers(List.of(server));

        AgentCoreSandboxClientFactory factory = new DefaultAgentCoreSandboxClientFactory(properties);
        SandboxGatewayConfig config = factory.configFor(serverId);
        SandboxClient client = factory.create(serverId);

        log.info("Sandbox enabled: {}", properties.getSandbox().isEnabled());
        log.info("Sandbox service URL: {}", serviceUrl);
        log.info("Core gateway URL: {}", config.getGatewayUrl());
        log.info("Core sandbox type: {}", config.getLauncherConfig().getSandboxType());
        log.info("Created client: {}", client.getClass().getName());
        String operation = option(args, "--operation=", "config");
        runOperation(args, operation, client);
    }

    private static void runOperation(String[] args, String operation, SandboxClient client) {
        switch (operation) {
            case "read-file" -> {
                String path = option(args, "--path=", "/tmp/demo.txt");
                ReadFileResult result = client.fs().readFile(path, "text", null, null, null, "UTF-8", 0, Map.of());
                log.info("read-file code: {}", result.getCode());
                log.info("read-file message: {}", result.getMessage());
                log.info("read-file content: {}", result.getData().getContentAsString());
            }
            case "shell" -> {
                String command = option(args, "--command=", "echo sandbox");
                ExecuteCmdResult result = client.shell().executeCmd(command, ".", 0, Map.of(), Map.of());
                log.info("shell code: {}", result.getCode());
                log.info("shell exit code: {}", result.getData().getExitCode());
                log.info("shell stdout: {}", result.getData().getStdout());
                log.info("shell stderr: {}", result.getData().getStderr());
            }
            case "code" -> {
                String code = option(args, "--code=", "print('sandbox')");
                String language = option(args, "--language=", "python");
                ExecuteCodeResult result = client.code().executeCode(code, language, 0, Map.of(), Map.of());
                log.info("code result: {}", result.getCode());
                log.info("code exit code: {}", result.getData().getExitCode());
                log.info("code stdout: {}", result.getData().getStdout());
                log.info("code stderr: {}", result.getData().getStderr());
            }
            default -> log.info("No sandbox operation executed. Use --operation=read-file|shell|code.");
        }
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
}
