/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal local MCP JSON-RPC server for the Agent Service demo.
 *
 * @since 2026-06-24
 */
public class MockMcpServerExample {
    private static final Logger log = LoggerFactory.getLogger(MockMcpServerExample.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18080"));

        MockMcpServerExample app = new MockMcpServerExample();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/mcp", app::handle);
        server.setExecutor(newServerExecutor());
        server.start();
        log.info("Mock MCP server started at http://127.0.0.1:{}/mcp", port);
    }

    private static ThreadPoolExecutor newServerExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
        Object method = request.get("method");
        if ("initialize".equals(method)) {
            writeJson(exchange, 200, response(request.get("id"),
                Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "serverInfo",
                    Map.of("name", "mock-mcp-demo", "version", "1.0.0"))));
            return;
        }
        if ("tools/list".equals(method)) {
            writeJson(exchange, 200, response(request.get("id"), Map.of("tools", List.of(
                Map.of("name", "demo_echo", "description", "Echo text from the demo MCP server", "inputSchema",
                    Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required",
                        List.of("text")))))));
            return;
        }
        if ("tools/call".equals(method)) {
            Map<String, Object> params = asMap(request.get("params"));
            Map<String, Object> arguments = asMap(params.get("arguments"));
            Object text = arguments.getOrDefault("text", "");
            writeJson(exchange, 200, response(request.get("id"),
                Map.of("content", List.of(Map.of("type", "text", "text", "demo_echo:" + text)))));
            return;
        }

        writeJson(exchange, 200, Map.of("jsonrpc", "2.0", "id", request.get("id"), "error",
            Map.of("code", -32601, "message", "Method not found")));
    }

    private static Map<String, Object> response(Object id, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("result", result);
        return payload;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
