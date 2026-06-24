/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal local sandbox HTTP server for validating the Service sandbox adapter.
 *
 * @since 2026-06-24
 */
public class MockSandboxServerExample {
    private static final Logger log = LoggerFactory.getLogger(MockSandboxServerExample.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path rootDir;

    private MockSandboxServerExample(Path rootDir) {
        this.rootDir = rootDir;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18090"));
        Path rootDir = options.containsKey("root-dir") ? Path.of(options.get("root-dir")) : null;

        MockSandboxServerExample app = new MockSandboxServerExample(rootDir);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/invoke", app::handle);
        server.setExecutor(newServerExecutor());
        server.start();

        log.info("Mock sandbox server started at http://127.0.0.1:{}/invoke", port);
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
            writeJson(exchange, 405, Map.of("code", 405, "message", "POST required", "data", null));
            return;
        }

        Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
        String opType = String.valueOf(request.getOrDefault("opType", ""));
        String method = String.valueOf(request.getOrDefault("method", ""));
        Map<String, Object> params = asMap(request.get("params"));

        if ("fs".equals(opType) && "readFile".equals(method)) {
            writeJson(exchange, 200, readFile(params));
            return;
        }
        if ("shell".equals(opType) && "executeCmd".equals(method)) {
            writeJson(exchange, 200, executeCmd(params));
            return;
        }
        if ("code".equals(opType) && "executeCode".equals(method)) {
            writeJson(exchange, 200, executeCode(params));
            return;
        }

        writeJson(exchange, 200, Map.of(
                "code", 400,
                "message", "Unsupported sandbox operation: " + opType + "." + method,
                "data", null));
    }

    private Map<String, Object> readFile(Map<String, Object> params) throws IOException {
        String path = String.valueOf(params.getOrDefault("path", "/tmp/demo.txt"));
        String mode = String.valueOf(params.getOrDefault("mode", "text"));
        String content = readFromRoot(path);
        return result(Map.of(
                "path", path,
                "content", content,
                "mode", mode));
    }

    private Map<String, Object> executeCmd(Map<String, Object> params) {
        String command = String.valueOf(params.getOrDefault("command", ""));
        String cwd = String.valueOf(params.getOrDefault("cwd", "."));
        return result(Map.of(
                "command", command,
                "cwd", cwd,
                "exitCode", 0,
                "stdout", "mock shell executed: " + command + "\n",
                "stderr", "",
                "shellType", "mock"));
    }

    private Map<String, Object> executeCode(Map<String, Object> params) {
        String code = String.valueOf(params.getOrDefault("code", ""));
        String language = String.valueOf(params.getOrDefault("language", "text"));
        return result(Map.of(
                "codeContent", code,
                "language", language,
                "exitCode", 0,
                "stdout", "mock code executed: " + language + ":" + code + "\n",
                "stderr", ""));
    }

    private String readFromRoot(String sandboxPath) throws IOException {
        if (rootDir == null) {
            return "mock sandbox file:" + sandboxPath;
        }
        String relativePath = sandboxPath.startsWith("/") ? sandboxPath.substring(1) : sandboxPath;
        Path resolved = rootDir.resolve(relativePath).normalize();
        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(normalizedRoot) || !Files.isRegularFile(resolved)) {
            return "mock sandbox file:" + sandboxPath;
        }
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> result(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", 0);
        payload.put("message", "success");
        payload.put("data", data);
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

    private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
