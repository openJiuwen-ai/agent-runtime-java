/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.sysop.config.ContainerScope;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests HTTP sandbox providers against a local mock sandbox service.
 *
 * @since 2026-06-24
 */
class HttpSandboxProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentLinkedQueue<Map<String, Object>> requests = new ConcurrentLinkedQueue<>();
    private final String sandboxType = "http_" + UUID.randomUUID().toString().replace("-", "");
    private final String isolationKey = "session_" + UUID.randomUUID().toString().replace("-", "");
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        SandboxRegistry.unregisterProvider(sandboxType, "fs");
        SandboxRegistry.unregisterProvider(sandboxType, "shell");
        SandboxRegistry.unregisterProvider(sandboxType, "code");
    }

    @Test
    void coreSandboxClientPostsFsReadFileToConfiguredHttpSandboxService() {
        startServer(exchange -> respondJson(exchange, Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "path", "/tmp/demo.txt",
                        "content", "remote-content",
                        "mode", "text"))));

        SandboxClient client = sandboxClient();

        ReadFileResult result = client.fs().readFile(
                "/tmp/demo.txt", "text", null, null, null, "UTF-8", 0, Map.of());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getContentAsString()).isEqualTo("remote-content");
        Map<String, Object> request = requests.peek();
        assertThat(request).containsEntry("opType", "fs");
        assertThat(request).containsEntry("method", "readFile");
        assertThat(request).containsEntry("isolationKey", isolationKey);
        assertThat(request).containsEntry("sandboxId", isolationKey);
        assertThat(request).extracting("params")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("path", "/tmp/demo.txt")
                .containsEntry("mode", "text");
    }

    @Test
    void coreSandboxClientPostsShellExecuteCmdToConfiguredHttpSandboxService() {
        startServer(exchange -> respondJson(exchange, Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "command", "echo ok",
                        "cwd", "/workspace",
                        "exitCode", 0,
                        "stdout", "ok\n",
                        "stderr", "",
                        "shellType", "sh"))));

        SandboxClient client = sandboxClient();

        ExecuteCmdResult result = client.shell().executeCmd(
                "echo ok", "/workspace", 2, Map.of("LANG", "C"), Map.of("pty", false));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getStdout()).isEqualTo("ok\n");
        Map<String, Object> request = requests.peek();
        assertThat(request).containsEntry("opType", "shell");
        assertThat(request).containsEntry("method", "executeCmd");
        assertThat(request).extracting("params")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("command", "echo ok")
                .containsEntry("cwd", "/workspace");
    }

    @Test
    void coreSandboxClientPostsCodeExecuteCodeToConfiguredHttpSandboxService() {
        startServer(exchange -> respondJson(exchange, Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "codeContent", "print('ok')",
                        "language", "python",
                        "exitCode", 0,
                        "stdout", "ok\n",
                        "stderr", ""))));

        SandboxClient client = sandboxClient();

        ExecuteCodeResult result = client.code().executeCode(
                "print('ok')", "python", 2, Map.of(), Map.of());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getStdout()).isEqualTo("ok\n");
        Map<String, Object> request = requests.peek();
        assertThat(request).containsEntry("opType", "code");
        assertThat(request).containsEntry("method", "executeCode");
        assertThat(request).extracting("params")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("code", "print('ok')")
                .containsEntry("language", "python");
    }

    @Test
    void httpSandboxProviderUsesConfiguredInvokePath() {
        startServer("/sandbox/invoke", exchange -> respondJson(exchange, Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "path", "/tmp/custom.txt",
                        "content", "custom-path",
                        "mode", "text"))));

        SandboxClient client = sandboxClient(Map.of("invoke_path", "/sandbox/invoke"));

        ReadFileResult result = client.fs().readFile(
                "/tmp/custom.txt", "text", null, null, null, "UTF-8", 0, Map.of());

        assertThat(result.getData().getContentAsString()).isEqualTo("custom-path");
        assertThat(requests).hasSize(1);
    }

    @Test
    void httpSandboxProviderReturnsIteratorForStreamingResponseArray() {
        startServer(exchange -> respondJson(exchange, List.of(
                Map.of("code", 0, "message", "chunk-1", "data",
                        Map.of("path", "/tmp/demo.txt", "chunkContent", "a", "mode", "text",
                                "chunkSize", 1, "chunkIndex", 0, "lastChunk", false)),
                Map.of("code", 0, "message", "chunk-2", "data",
                        Map.of("path", "/tmp/demo.txt", "chunkContent", "b", "mode", "text",
                                "chunkSize", 1, "chunkIndex", 1, "lastChunk", true)))));

        SandboxClient client = sandboxClient();

        List<String> chunks = new java.util.ArrayList<>();
        client.fs().readFileStream("/tmp/demo.txt", "text", null, null, null, "UTF-8", 0, Map.of())
                .forEachRemaining(result -> chunks.add(result.getData().getChunkContentAsString()));

        assertThat(chunks).containsExactly("a", "b");
        Map<String, Object> request = requests.peek();
        assertThat(request).containsEntry("opType", "fs");
        assertThat(request).containsEntry("method", "readFileStream");
    }

    private SandboxClient sandboxClient() {
        return sandboxClient(Map.of());
    }

    private SandboxClient sandboxClient(Map<String, Object> extraParams) {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setTimeoutMs(3000);
        properties.getSandbox().getRetry().setMax(0);
        properties.getSandbox().getCircuitBreaker().setEnabled(false);

        AgentCoreExternalProperties.SandboxServer sandboxServer =
                new AgentCoreExternalProperties.SandboxServer();
        sandboxServer.setServerId("default");
        sandboxServer.setServiceUrl("http://127.0.0.1:" + server.getAddress().getPort());
        sandboxServer.setSandboxType(sandboxType);
        sandboxServer.setLauncherType("pre_deploy");
        sandboxServer.setIsolationKey(isolationKey);
        sandboxServer.setContainerScope(ContainerScope.CUSTOM);
        sandboxServer.setExtraParams(extraParams);
        properties.getSandbox().setServers(List.of(sandboxServer));

        return new DefaultAgentCoreSandboxClientFactory(properties).create("default");
    }

    private void startServer(ExchangeHandler handler) {
        startServer("/invoke", handler);
    }

    private void startServer(String path, ExchangeHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                requests.add(readJsonRequest(exchange));
                handler.handle(exchange);
            });
            server.start();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Map<String, Object> readJsonRequest(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return MAPPER.readValue(body, new TypeReference<>() {
        });
    }

    private static void respondJson(HttpExchange exchange, Object response) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        /**
         * Handles a test HTTP exchange.
         *
         * @param exchange HTTP exchange received by the local test server
         * @throws IOException when reading or writing the exchange fails
         */
        void handle(HttpExchange exchange) throws IOException;
    }
}
