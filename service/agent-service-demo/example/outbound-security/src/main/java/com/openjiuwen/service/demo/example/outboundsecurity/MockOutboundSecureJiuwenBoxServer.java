/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Local HTTPS jiuwenbox mock with optional Bearer token enforcement for Issue #25 sandbox demos.
 *
 * @since 0.1.0
 */
public class MockOutboundSecureJiuwenBoxServer {
    private static final Logger log = LoggerFactory.getLogger(MockOutboundSecureJiuwenBoxServer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SANDBOX_ID = "mock-secure-sandbox";

    private final String bearerToken;

    private HttpsServer server;

    private OutboundTlsMaterialGenerator.Material tlsMaterial;

    public MockOutboundSecureJiuwenBoxServer(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    /**
     * Starts the server on the given port using ephemeral TLS material.
     *
     * @param port listen port; {@code 0} picks a free port
     * @throws Exception if startup fails
     */
    public void start(int port) throws Exception {
        tlsMaterial = OutboundTlsMaterialGenerator.generate();
        server = createServer(port, tlsMaterial);
        server.start();
        log.info("Mock outbound secure jiuwenbox server started at https://127.0.0.1:{} (token={})",
            server.getAddress().getPort(),
            bearerToken == null || bearerToken.isBlank() ? "<none>" : bearerToken);
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
     * Returns the HTTPS service base URL.
     *
     * @return base URL without trailing slash
     */
    public String baseUrl() {
        return "https://127.0.0.1:" + port();
    }

    /**
     * Standalone entry point.
     *
     * @param args {@code --port=18490} {@code --token=demo-outbound-token}
     * @throws Exception Exception
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18490"));
        String token = options.getOrDefault("token", OutboundSecurityMcpClientExample.DEMO_TOKEN);
        MockOutboundSecureJiuwenBoxServer mockServer = new MockOutboundSecureJiuwenBoxServer(token);
        mockServer.start(port);
        exportTlsMaterial(mockServer.tlsMaterial());
        Runtime.getRuntime().addShutdownHook(new Thread(mockServer::stop));
        Thread.currentThread().join();
    }

    /**
     * Writes the randomly generated store password next to the generated stores and logs its location,
     * so a separately launched service can be wired to this mock without the password entering the logs.
     *
     * @param material TLS material generated for this run
     * @throws IOException if the password file cannot be written
     */
    private static void exportTlsMaterial(OutboundTlsMaterialGenerator.Material material) throws IOException {
        java.nio.file.Path passwordFile = material.directory().resolve("store-password.txt");
        Files.writeString(passwordFile, material.password());
        log.info("Client trust store: {}", material.clientTrustStoreLocation());
        log.info("Store password written to {}; export DEMO_OUTBOUND_TRUST_STORE_PASSWORD from it", passwordFile);
    }

    private HttpsServer createServer(int port, OutboundTlsMaterialGenerator.Material material) throws Exception {
        char[] password = material.password().toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = Files.newInputStream(material.serverKeyStore())) {
            keyStore.load(inputStream, password);
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        httpsServer.createContext("/api/v1/sandboxes", this::handleSandboxes);
        httpsServer.setExecutor(newServerExecutor());
        return httpsServer;
    }

    private void handleSandboxes(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            writeJson(exchange, 401, Map.of("error", "Unauthorized"));
            return;
        }

        String method = exchange.getRequestMethod();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        Map<String, String> query = queryParameters(uri);

        if ("POST".equals(method) && "/api/v1/sandboxes".equals(path)) {
            writeJson(exchange, 200, Map.of("id", SANDBOX_ID));
            return;
        }
        if ("GET".equals(method) && sandboxPath("/download").equals(path)) {
            String sandboxPath = query.getOrDefault("sandbox_path", "/tmp/demo.txt");
            writeBytes(exchange, 200, ("secure jiuwenbox file:" + sandboxPath).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("DELETE".equals(method) && sandboxPath("").equals(path)) {
            writeBytes(exchange, 204, new byte[0]);
            return;
        }
        writeJson(exchange, 404, Map.of("message", "sandbox route not found"));
    }

    private boolean authorize(HttpExchange exchange) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return ("Bearer " + bearerToken).equals(authorization);
    }

    private String sandboxPath(String suffix) {
        return "/api/v1/sandboxes/" + SANDBOX_ID + suffix;
    }

    private static Map<String, String> queryParameters(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return List.of(rawQuery.split("&")).stream()
            .map(part -> part.split("=", 2))
            .collect(Collectors.toMap(
                parts -> decode(parts[0]),
                parts -> parts.length > 1 ? decode(parts[1]) : "",
                (left, right) -> right,
                LinkedHashMap::new));
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
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

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void writeBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(MockOutboundSecureJiuwenBoxServer::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for demo temp files.
        }
    }
}
