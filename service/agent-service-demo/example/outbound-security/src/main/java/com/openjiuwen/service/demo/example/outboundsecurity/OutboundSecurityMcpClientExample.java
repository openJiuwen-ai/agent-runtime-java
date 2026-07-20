/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.foundation.tool.mcp.McpToolCard;
import com.openjiuwen.core.foundation.tool.mcp.client.StreamableHttpClient;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.security.ExternalAuthProperties;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport.PreparedOutboundSecurity;
import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;
import com.openjiuwen.service.demo.example.outboundsecurity.support.OutboundTlsMaterialGenerator;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end outbound MCP client demo: HTTPS + Bearer auth via Issue #25 adapters wiring.
 *
 * <p>Starts an embedded mock HTTPS MCP server, builds {@link McpServerConfig} through
 * {@link ExternalOutboundSecuritySupport}, then lists tools through Core {@link StreamableHttpClient}.
 *
 * @since 0.1.0
 */
public final class OutboundSecurityMcpClientExample {
    /** Demo bearer token shared with {@link MockOutboundSecureMcpServer}. */
    public static final String DEMO_TOKEN = "demo-outbound-token";
    private static final Logger log = LoggerFactory.getLogger(OutboundSecurityMcpClientExample.class);

    private OutboundSecurityMcpClientExample() {
    }

    /**
     * Runs the outbound MCP HTTPS + Bearer demo.
     *
     * @param args optional {@code --port=} override; {@code 0} picks a free port
     * @throws Exception if the demo fails
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(parseArgs(args).getOrDefault("port", "0"));
        List<String> tools = runDemo(port);
        log.info("Listed MCP tools: {}", tools);
    }

    /**
     * Runs the demo on the given port (used by tests).
     *
     * @param port HTTPS listen port; {@code 0} picks a free port
     * @return listed tool names
     * @throws Exception if the demo fails
     */
    public static List<String> runDemo(int port) throws Exception {
        MockOutboundSecureMcpServer mockServer = new MockOutboundSecureMcpServer(DEMO_TOKEN);
        mockServer.start(port);
        try {
            return listToolsThroughOutboundSecurity(mockServer);
        } finally {
            mockServer.stop();
        }
    }

    /**
     * Lists tools from the given mock server using outbound TLS + Bearer wiring.
     *
     * @param mockServer started mock HTTPS MCP server
     * @return listed tool names
     * @throws Exception if listing fails
     */
    public static List<String> listToolsThroughOutboundSecurity(MockOutboundSecureMcpServer mockServer)
        throws Exception {
        OutboundTlsMaterialGenerator.Material tlsMaterial = mockServer.tlsMaterial();
        ExternalTlsConfig tlsConfig = new ExternalTlsConfig();
        tlsConfig.setEnabled(true);
        tlsConfig.setTrustStore(tlsMaterial.clientTrustStoreLocation());
        tlsConfig.setTrustStorePassword(OutboundTlsMaterialGenerator.PASSWORD);
        tlsConfig.setTrustStoreType("PKCS12");
        tlsConfig.setVerifyHostname(false);

        ExternalAuthProperties authConfig = new ExternalAuthProperties();
        authConfig.setType("bearer");
        authConfig.setToken(DEMO_TOKEN);

        ExternalOutboundSecuritySupport support = ExternalOutboundSecuritySupport
            .createDefault(new PassthroughCredentialDecryptor());
        int actualPort = mockServer.port();
        PreparedOutboundSecurity prepared = support.prepare(
            new ExternalTargetRef("MCP", "demo-secure-mcp",
                "https://127.0.0.1:" + actualPort + "/mcp", CredentialSceneType.MCP_AUTH_TOKEN),
            tlsConfig, authConfig, Duration.ofSeconds(5));

        McpServerConfig coreConfig = McpServerConfig.builder()
            .serverId("demo-secure-mcp")
            .serverName("demo-secure-mcp-tools")
            .serverPath("https://127.0.0.1:" + actualPort + "/mcp")
            .clientType("streamable_http")
            .build();
        prepared.applyAuthToMaps(coreConfig.getAuthHeaders(), coreConfig.getAuthQueryParams());
        Map<String, Object> params = new LinkedHashMap<>();
        prepared.injectParams(params);
        coreConfig.setParams(params);

        McpClient client = new StreamableHttpClient(coreConfig);
        client.connect(1, 5f);
        List<Object> tools = client.listTools(5f);
        client.disconnect(1f);

        List<String> toolNames = tools.stream()
            .map(tool -> tool instanceof McpToolCard card ? card.getName() : String.valueOf(tool))
            .toList();
        log.info("Outbound secure MCP demo succeeded, tools={}", toolNames);
        return toolNames;
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
