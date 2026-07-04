/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.singleagent.schema.AgentResult;
import com.openjiuwen.core.singleagent.schema.Artifact;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientFactory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Validates A2A remote adapter against the in-test mock server.
 */
class RemoteExampleLocalServerTest {
    @Test
    void remoteAdapterExampleCanCallLocalMockA2aServer() throws Exception {
        int port = freePort();
        Thread serverThread = new Thread(() -> {
            try {
                com.openjiuwen.service.demo.support.remote.MockA2ARemoteServerExample
                        .main(new String[]{"--port=" + port});
            } catch (Exception ex) {
                throw new IllegalStateException("mock A2A server failed", ex);
            }
        }, "mock-a2a-remote-server");
        serverThread.setUncaughtExceptionHandler((unused, error) -> {
            throw new AssertionError(error);
        });
        serverThread.setDaemon(true);
        serverThread.start();

        waitUntilPortOpen(port, 10_000);

        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getRemote().setTimeoutMs(3000);
        properties.getRemote().setRetryInvoke(false);
        properties.getRemote().getRetry().setMax(0);

        AgentCoreExternalProperties.RemoteClientEndpoint remoteClient = new AgentCoreExternalProperties.RemoteClientEndpoint();
        remoteClient.setId("demo-a2a-remote");
        remoteClient.setName("Demo A2A Remote");
        remoteClient.setProtocol("A2A");
        remoteClient.setUrl("http://127.0.0.1:" + port + "/a2a/jsonrpc");
        properties.getRemote().setClients(List.of(remoteClient));

        AgentCoreRemoteClientFactory factory = new DefaultAgentCoreRemoteClientFactory(properties,
                new DefaultAgentCoreRemoteClientDecoratorFactory());
        RemoteClient client = factory.create("demo-a2a-remote");

        assertThat(client.getClass().getName())
                .isEqualTo("com.openjiuwen.service.adapters.agentcore.external.DecoratingRemoteClient");

        Object result = client.invoke(Map.of("message", "hello remote", "conversation_id", "demo-session"), null);
        assertThat(result).isInstanceOf(AgentResult.class);
        AgentResult agentResult = (AgentResult) result;
        assertThat(String.valueOf(agentResult.getStatus())).isEqualTo("completed");
        assertThat(agentResult.getSessionId()).isEqualTo("demo-session");
        assertThat(firstText(agentResult.getArtifacts())).isEqualTo("mock a2a response: hello remote");
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

    private static void waitUntilPortOpen(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (isPortOpen(port)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("mock A2A remote server did not start on port " + port);
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
