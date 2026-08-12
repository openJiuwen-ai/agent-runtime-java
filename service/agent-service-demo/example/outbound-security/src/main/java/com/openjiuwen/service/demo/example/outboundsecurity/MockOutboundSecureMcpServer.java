/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.demo.example.outboundsecurity.support.OutboundTlsMaterialGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Local HTTPS MCP JSON-RPC server with optional Bearer token enforcement for Issue #25 demos.
 *
 * @since 0.1.0
 */
public class MockOutboundSecureMcpServer {
    private static final Logger log = LoggerFactory.getLogger(MockOutboundSecureMcpServer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String bearerToken;

    private HttpsServer server;

    private OutboundTlsMaterialGenerator.Material tlsMaterial;

    public MockOutboundSecureMcpServer(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    /**
     * Starts the server on the given port using ephemeral TLS material.
     *
     * @param port listen port
     * @throws Exception if startup fails
     */
    public void start(int port) throws Exception {
        tlsMaterial = OutboundTlsMaterialGenerator.generate();
        server = createServer(port, tlsMaterial);
        server.start();
        log.info("Mock outbound secure MCP server started at https://127.0.0.1:{}/mcp (token={})",
                server.getAddress().getPort(), bearerToken == null || bearerToken.isBlank() ? "<none>" : bearerToken);
    }

    /**
     * Stops the server and deletes generated TLS material.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (tlsMaterial != null) {
            deleteRecursively(tlsMaterial.directory());
            tlsMaterial = null;
        }
    }

    /**
     * Returns the TLS material generated for this server instance.
     *
     * @return TLS material, or null when not started
     */
    public OutboundTlsMaterialGenerator.Material tlsMaterial() {
        return tlsMaterial;
    }

    /**
     * Returns the HTTPS listen port after {@link #start(int)}.
     *
     * @return bound port, or {@code -1} when not started
     */
    public int port() {
        return server != null ? server.getAddress().getPort() : -1;
    }

    /**
     * Standalone entry point.
     *
     * @param args {@code --port=18443} {@code --token=demo-outbound-token}
     * @throws Exception Exception
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18443"));
        String token = options.getOrDefault("token", "demo-outbound-token");
        MockOutboundSecureMcpServer mockServer = new MockOutboundSecureMcpServer(token);
        mockServer.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(mockServer::stop));
        Thread.currentThread().join();
    }

    private HttpsServer createServer(int port, OutboundTlsMaterialGenerator.Material material) throws Exception {
        char[] password = OutboundTlsMaterialGenerator.PASSWORD.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = Files.newInputStream(material.serverKeyStore())) {
            keyStore.load(inputStream, password);
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        httpsServer.createContext("/mcp", this::handle);
        httpsServer.setExecutor(newServerExecutor());
        return httpsServer;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + bearerToken).equals(authorization)) {
                writeJson(exchange, 401, Map.of("error", "Unauthorized"));
                return;
            }
        }

        Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), MAP_TYPE);
        Object method = request.get("method");
        if ("initialize".equals(method)) {
            writeJson(exchange, 200, response(request.get("id"), Map.of("protocolVersion", "2024-11-05", "capabilities",
                    Map.of(), "serverInfo", Map.of("name", "mock-outbound-secure-mcp", "version", "1.0.0"))));
            return;
        }
        if ("notifications/initialized".equals(method)) {
            writeNoContent(exchange);
            return;
        }
        if ("tools/list".equals(method)) {
            writeJson(exchange, 200, response(request.get("id"), Map.of("tools",
                    List.of(Map.of("name", "secure_echo", "description", "Echo tool on HTTPS MCP with outbound auth",
                            "inputSchema", Map.of("type", "object", "properties",
                                    Map.of("text", Map.of("type", "string")), "required", List.of("text")))))));
            return;
        }
        if ("tools/call".equals(method)) {
            Map<String, Object> params = asMap(request.get("params"));
            Map<String, Object> arguments = asMap(params.get("arguments"));
            Object text = arguments.getOrDefault("text", "");
            writeJson(exchange, 200, response(request.get("id"),
                    Map.of("content", List.of(Map.of("type", "text", "text", "secure_echo:" + text)))));
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

    private static ThreadPoolExecutor newServerExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy());
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

    private static void writeNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void deleteRecursively(java.nio.file.Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(MockOutboundSecureMcpServer::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for demo temp files.
        }
    }
}
