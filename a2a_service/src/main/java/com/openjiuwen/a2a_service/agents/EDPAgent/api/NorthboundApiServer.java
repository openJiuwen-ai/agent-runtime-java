package com.openjiuwen.a2a_service.agents.EDPAgent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentRuntime;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NorthboundApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NorthboundApiService apiService;
    private final HttpServer server;
    private final ExecutorService executorService;

    public NorthboundApiServer(EDPAgentRuntime runtime, int port) throws IOException {
        this(runtime, "0.0.0.0", port);
    }

    public NorthboundApiServer(EDPAgentRuntime runtime, String host, int port) throws IOException {
        this(new NorthboundApiService(runtime), host, port);
    }

    public NorthboundApiServer(NorthboundApiService apiService, int port) throws IOException {
        this(apiService, "0.0.0.0", port);
    }

    public NorthboundApiServer(NorthboundApiService apiService, String host, int port) throws IOException {
        this.apiService = apiService;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executorService = Executors.newCachedThreadPool();
        this.server.setExecutor(executorService);
        registerRoutes();
    }

    private void registerRoutes() {
        server.createContext("/health", exchange -> handleJson(exchange, this::handleHealth));
        server.createContext("/invoke", exchange -> handleInvoke(exchange));
        server.createContext("/interrupt", exchange -> handleInterrupt(exchange));
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        executorService.shutdownNow();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        writeJson(exchange, 200, apiService.health());
    }

    private void handleInvoke(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        try {
            InvokeRequest request = readJson(exchange, InvokeRequest.class);
            List<String> lines = apiService.invoke(request);
            if (request.isStream()) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "text/event-stream; charset=utf-8");
                headers.set("Cache-Control", "no-cache");
                byte[] body = String.join("", lines).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(body);
                }
                return;
            }
            writeJson(exchange, 200, Map.of("status", "completed", "query", request.getQuery()));
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, Map.of("detail", e.getMessage()));
        }
    }

    private void handleInterrupt(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length != 4 || !"resolve".equals(parts[3])) {
            writeJson(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        String interruptId = parts[2];
        try {
            InterruptResolveRequest request = readJson(exchange, InterruptResolveRequest.class);
            writeJson(exchange, 200, apiService.resolveInterrupt(interruptId, request));
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, Map.of("detail", e.getMessage()));
        }
    }

    private <T> void handleJson(HttpExchange exchange, JsonHandler<T> handler) throws IOException {
        try {
            handler.handle(exchange);
        } finally {
            exchange.close();
        }
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return MAPPER.readValue(inputStream, type);
        }
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    @FunctionalInterface
    private interface JsonHandler<T> {
        void handle(HttpExchange exchange) throws IOException;
    }
}
