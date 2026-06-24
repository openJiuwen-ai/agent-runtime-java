/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.remote;

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
 * Minimal local A2A JSON-RPC server for validating the Service remote adapter.
 *
 * @since 2026-06-24
 */
public class MockA2ARemoteServerExample {
    private static final Logger log = LoggerFactory.getLogger(MockA2ARemoteServerExample.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18082"));

        MockA2ARemoteServerExample app = new MockA2ARemoteServerExample();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/a2a/jsonrpc", app::handle);
        server.setExecutor(newServerExecutor());
        server.start();

        log.info("Mock A2A remote server started at http://127.0.0.1:{}/a2a/jsonrpc", port);
    }

    private static ThreadPoolExecutor newServerExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, jsonRpcError(null, -32600, "POST required"));
            return;
        }

        Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
        String method = String.valueOf(request.getOrDefault("method", ""));
        if (!"SendMessage".equals(method)) {
            writeJson(exchange, 200, jsonRpcError(request.get("id"), -32601, "Unsupported method: " + method));
            return;
        }

        writeJson(exchange, 200, sendMessageResponse(request));
    }

    private Map<String, Object> sendMessageResponse(Map<String, Object> request) {
        Map<String, Object> params = asMap(request.get("params"));
        Map<String, Object> message = asMap(params.get("message"));
        String text = firstText(message);
        Object contextId = message.get("contextId");
        Object taskId = message.getOrDefault("taskId", contextId);

        Map<String, Object> responseMessage = new LinkedHashMap<>();
        responseMessage.put("messageId", "mock-a2a-response");
        responseMessage.put("role", "ROLE_AGENT");
        if (contextId != null) {
            responseMessage.put("contextId", String.valueOf(contextId));
        }
        if (taskId != null) {
            responseMessage.put("taskId", String.valueOf(taskId));
        }
        responseMessage.put("parts", List.of(Map.of("text", "mock a2a response: " + text)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", responseMessage);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", request.get("id"));
        payload.put("result", result);
        return payload;
    }

    private Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("error", error);
        return payload;
    }

    private String firstText(Map<String, Object> message) {
        Object parts = message.get("parts");
        if (!(parts instanceof List<?> list)) {
            return "";
        }
        for (Object item : list) {
            Map<String, Object> part = asMap(item);
            Object text = part.get("text");
            if (text != null) {
                return String.valueOf(text);
            }
        }
        return "";
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

    private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
