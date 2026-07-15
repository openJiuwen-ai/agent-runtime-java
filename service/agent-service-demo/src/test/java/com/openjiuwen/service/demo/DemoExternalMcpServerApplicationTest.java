/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tests demo MCP profile behavior against a local MCP server.
 *
 * @since 2026-06-24
 */
@SpringBootTest(properties = {
    "openjiuwen.service.llm.provider=DemoExternalMcpProvider",
    "openjiuwen.service.llm.api-key=test-key", "openjiuwen.service.llm.api-base=mirror://demo-external-mcp-server",
    "openjiuwen.service.llm.model-name=test-model", "openjiuwen.service.llm.auto-discover=false"
})
@ActiveProfiles("mcp")
class DemoExternalMcpServerApplicationTest {
    private static final LocalMcpServer MCP_SERVER = LocalMcpServer.start();

    @Autowired
    private AgentHandler agentHandler;

    @DynamicPropertySource
    static void configureExternalMcpServer(DynamicPropertyRegistry registry) {
        registry.add("DEMO_MCP_SERVER_ID", () -> "demo-mcp-server");
        registry.add("DEMO_MCP_SERVER_NAME", () -> "demo-mcp-tools");
        registry.add("DEMO_MCP_SERVER_PATH", MCP_SERVER::endpoint);
        registry.add("DEMO_MCP_RETRY_MAX", () -> "0");
    }

    @AfterEach
    void stopRunner() {
        agentHandler.stop();
        Runner.stop();
    }

    @AfterAll
    static void stopMcpServer() {
        MCP_SERVER.stop();
    }

    @Test
    @Tag("smoke")
    void demoStartsLocalMcpServerAndRegistersItsTools() throws Exception {
        List<ToolInfo> toolInfos = Runner.resourceMgr()
            .getMcpToolInfos(null, "demo-mcp-server", null, null, TagMatchStrategy.ANY, false, false);

        assertThat(toolInfos).extracting(ToolInfo::getName).containsExactly("demo_echo");

        Object tools = Runner.resourceMgr()
            .getMcpTool("demo_echo", "demo-mcp-server", null, null, TagMatchStrategy.ANY, false);
        assertThat(tools).asList().hasSize(1);
        if (!(tools instanceof List<?> toolList)) {
            throw new AssertionError("Expected registered MCP tools");
        }
        Object tool = toolList.get(0);
        if (!(tool instanceof Tool demoTool)) {
            throw new AssertionError("Expected demo MCP tool");
        }
        Object invokeResult = demoTool.invoke(Map.of("text", "hello"));
        assertThat(invokeResult).isEqualTo(Map.of("result", "demo_echo:hello"));
    }

    private static class LocalMcpServer {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

        private final HttpServer server;

        private final ExecutorService executor;

        private LocalMcpServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static LocalMcpServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = newServerExecutor();
                LocalMcpServer localServer = new LocalMcpServer(server, executor);
                server.createContext("/mcp", localServer::handle);
                server.setExecutor(executor);
                server.start();
                return localServer;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to start demo MCP server", ex);
            }
        }

        private static ThreadPoolExecutor newServerExecutor() {
            return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy());
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
            Object method = request.get("method");
            if ("initialize".equals(method)) {
                writeJson(exchange, response(request.get("id"),
                    Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "serverInfo",
                        Map.of("name", "demo-mcp-server", "version", "1.0.0"))));
                return;
            }
            if ("tools/list".equals(method)) {
                writeJson(exchange, response(request.get("id"), Map.of("tools", List.of(
                    Map.of("name", "demo_echo", "description", "Echo from demo MCP server", "inputSchema",
                        Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))))))));
                return;
            }
            if ("tools/call".equals(method)) {
                Map<String, Object> params = asMap(request.get("params"));
                Map<String, Object> arguments = asMap(params.get("arguments"));
                Object text = arguments.getOrDefault("text", "");
                writeJson(exchange, response(request.get("id"),
                    Map.of("content", List.of(Map.of("type", "text", "text", "demo_echo:" + text)))));
                return;
            }
            writeJson(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "error",
                Map.of("code", -32601, "message", "Method not found")));
        }

        private Map<String, Object> response(Object id, Map<String, Object> result) {
            return Map.of("jsonrpc", "2.0", "id", id, "result", result);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> asMap(Object value) {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        private void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
