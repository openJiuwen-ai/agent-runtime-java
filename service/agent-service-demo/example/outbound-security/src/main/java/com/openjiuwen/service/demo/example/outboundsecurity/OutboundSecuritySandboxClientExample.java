/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.common.security.ExternalAuthProperties;
import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;
import com.openjiuwen.service.demo.example.outboundsecurity.support.OutboundTlsMaterialGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end outbound Sandbox client demo: HTTPS + Bearer auth via Issue #25 adapters wiring.
 *
 * <p>Uses {@link DefaultAgentCoreSandboxClientFactory} to inject {@code _ojw_okhttp_client} into Core
 * {@link SandboxClient}, then reads a file through the jiuwenbox provider.
 *
 * @since 0.1.0
 */
public final class OutboundSecuritySandboxClientExample {
    private static final Logger log = LoggerFactory.getLogger(OutboundSecuritySandboxClientExample.class);

    private static final String DEMO_PATH = "/tmp/demo.txt";

    private OutboundSecuritySandboxClientExample() {
    }

    /**
     * Runs the outbound sandbox HTTPS + Bearer demo.
     *
     * @param args optional {@code --port=} override; {@code 0} picks a free port
     * @throws Exception if the demo fails
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(parseArgs(args).getOrDefault("port", "0"));
        String content = runDemo(port);
        log.info("Sandbox read-file content: {}", content);
    }

    /**
     * Runs the demo on the given port (used by tests).
     *
     * @param port HTTPS listen port; {@code 0} picks a free port
     * @return downloaded file content
     * @throws Exception if the demo fails
     */
    public static String runDemo(int port) throws Exception {
        MockOutboundSecureJiuwenBoxServer mockServer = new MockOutboundSecureJiuwenBoxServer(
            OutboundSecurityMcpClientExample.DEMO_TOKEN);
        mockServer.start(port);
        try {
            return readFileThroughOutboundSecurity(mockServer);
        } finally {
            mockServer.stop();
        }
    }

    /**
     * Reads a file from the given mock server using outbound TLS + Bearer wiring.
     *
     * @param mockServer started mock HTTPS jiuwenbox server
     * @return file content
     * @throws Exception if the read fails
     */
    public static String readFileThroughOutboundSecurity(MockOutboundSecureJiuwenBoxServer mockServer)
        throws Exception {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setTimeoutMs(5000);
        properties.getSandbox().getRetry().setMax(0);
        properties.getSandbox().getCircuitBreaker().setEnabled(false);

        AgentCoreExternalProperties.SandboxServer server = new AgentCoreExternalProperties.SandboxServer();
        server.setServerId("demo-secure-sandbox");
        server.setServiceUrl(mockServer.baseUrl());
        server.setSandboxType("jiuwenbox");
        server.setLauncherType("pre_deploy");
        server.setRootPath(".");

        ExternalTlsConfig tlsConfig = server.getTls();
        OutboundTlsMaterialGenerator.Material tlsMaterial = mockServer.tlsMaterial();
        tlsConfig.setEnabled(true);
        tlsConfig.setTrustStore(tlsMaterial.clientTrustStoreLocation());
        tlsConfig.setTrustStorePassword(OutboundTlsMaterialGenerator.PASSWORD);
        tlsConfig.setTrustStoreType("PKCS12");
        tlsConfig.setVerifyHostname(false);

        ExternalAuthProperties authConfig = server.getAuth();
        authConfig.setType("bearer");
        authConfig.setToken(OutboundSecurityMcpClientExample.DEMO_TOKEN);

        properties.getSandbox().setServers(List.of(server));

        DefaultAgentCoreSandboxClientFactory factory = new DefaultAgentCoreSandboxClientFactory(properties);
        SandboxClient client = factory.create("demo-secure-sandbox");
        ReadFileResult result = client.fs().readFile(DEMO_PATH, "text", null, null, null, "UTF-8", 0, Map.of());
        String content = result.getData().getContentAsString();
        log.info("Outbound secure sandbox demo succeeded, content={}", content);
        return content;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }
            int separator = arg.indexOf('=');
            options.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return options;
    }
}
