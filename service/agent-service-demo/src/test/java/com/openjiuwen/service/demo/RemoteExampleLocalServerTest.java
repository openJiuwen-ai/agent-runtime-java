/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.DefaultAgentCoreRemoteClientFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Validates A2A remote adapter against the in-test mock server.
 */
class RemoteExampleLocalServerTest {
    private ExecutorService serverPool;

    @AfterEach
    void shutdownServerPool() {
        if (serverPool != null) {
            serverPool.shutdownNow();
        }
    }

    @Test
    void remoteAdapterExampleCanCallLocalMockA2aServer() throws Exception {
        int port = freePort();
        serverPool = newServerPool();
        serverPool.execute(() -> {
            try {
                com.openjiuwen.service.demo.support.remote.MockA2ARemoteServerExample.main(
                    new String[] {"--port=" + port});
            } catch (Exception ex) {
                throw new IllegalStateException("mock A2A server failed", ex);
            }
        });

        waitUntilPortOpen(port, 10_000);

        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getRemote().setTimeoutMs(3000);
        properties.getRemote().setRetryInvoke(false);
        properties.getRemote().getRetry().setMax(0);

        AgentCoreExternalProperties.RemoteClientEndpoint remoteClient
            = new AgentCoreExternalProperties.RemoteClientEndpoint();
        remoteClient.setId("demo-a2a-remote");
        remoteClient.setName("Demo A2A Remote");
        remoteClient.setProtocol("A2A");
        remoteClient.setUrl("http://127.0.0.1:" + port + "/a2a/jsonrpc");
        properties.getRemote().setClients(List.of(remoteClient));

        AgentCoreRemoteClientFactory factory = new DefaultAgentCoreRemoteClientFactory(properties,
            new DefaultAgentCoreRemoteClientDecoratorFactory());
        RemoteClient client = factory.create("demo-a2a-remote");

        assertThat(client.getClass().getName()).isEqualTo(
            "com.openjiuwen.service.adapters.agentcore.external.DecoratingRemoteClient");

        Object result = client.invoke(Map.of("query", "hello remote", "conversation_id", "demo-session"), null);
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(String.valueOf(resultMap.get("status"))).isEqualTo("completed");
        assertThat(String.valueOf(resultMap.get("sessionId"))).isEqualTo("demo-session");
        assertThat(firstTextFromMap(resultMap)).isEqualTo("mock a2a response: hello remote");
    }

    @SuppressWarnings("unchecked")
    private static String firstTextFromMap(Map<?, ?> resultMap) {
        Object artifacts = resultMap.get("artifacts");
        if (!(artifacts instanceof List<?> artifactList)) {
            return "";
        }
        for (Object artifactObj : artifactList) {
            if (!(artifactObj instanceof Map<?, ?> artifact)) {
                continue;
            }
            Object parts = artifact.get("parts");
            if (!(parts instanceof List<?> partList)) {
                continue;
            }
            for (Object partObj : partList) {
                if (partObj instanceof Map<?, ?> part && part.get("text") != null) {
                    return String.valueOf(part.get("text"));
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

    private static ExecutorService newServerPool() {
        ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = defaultThreadFactory.newThread(runnable);
            thread.setName("mock-a2a-remote-server");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
