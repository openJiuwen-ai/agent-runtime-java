/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.security.ExternalAuthProperties;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests MCP outbound auth mapping through {@link DefaultExternalSvcAdapterRegistrar}.
 */
class DefaultExternalSvcAdapterRegistrarSecurityTest {
    @Test
    void toCoreConfigMapsBearerAuthHeaders() throws Exception {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        AgentCoreExternalProperties.McpServer server = new AgentCoreExternalProperties.McpServer();
        server.setServerId("srv-auth");
        server.setServerName("tools");
        server.setServerPath("https://localhost:9000/mcp");
        server.setClientType("sse");
        ExternalAuthProperties auth = server.getAuth();
        auth.setType("bearer");
        auth.setEncryptedToken("ENC(token)");
        properties.getMcp().setServers(List.of(server));

        DefaultExternalSvcAdapterRegistrar registrar = new DefaultExternalSvcAdapterRegistrar(properties,
            new DefaultAgentCoreMcpClientDecoratorFactory(), new DefaultAgentCoreRemoteClientDecoratorFactory(),
            List.of(), List.of(), createSecuritySupport());

        var method = DefaultExternalSvcAdapterRegistrar.class.getDeclaredMethod("toCoreConfig",
            AgentCoreExternalProperties.McpServer.class);
        method.setAccessible(true);
        Object invoked = method.invoke(registrar, server);

        assertThat(invoked).isInstanceOf(McpServerConfig.class);
        if (invoked instanceof McpServerConfig coreConfig) {
            assertThat(coreConfig.getAuthHeaders()).containsEntry("Authorization", "Bearer token");
        }
    }

    private static ExternalOutboundSecuritySupport createSecuritySupport() {
        CredentialDecryptor decryptor = new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                if (sceneType == CredentialSceneType.MCP_AUTH_TOKEN) {
                    return ciphertext.replace("ENC(", "").replace(")", "");
                }
                return ciphertext;
            }
        };
        return ExternalOutboundSecuritySupport.createDefault(decryptor);
    }
}
