/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for remote Versatile RESTful workflow services.
 */
public class VersatileHttpClient {

    private final VersatileProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VersatileHttpClient(VersatileProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build(), new ObjectMapper());
    }

    VersatileHttpClient(VersatileProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> postQuery(Map<String, Object> body) throws IOException, InterruptedException {
        return postJson(resolvePath(properties.getQueryPath()), body);
    }

    public Map<String, Object> postStreamQuery(Map<String, Object> body) throws IOException, InterruptedException {
        return postJson(resolvePath(properties.getStreamPath()), body);
    }

    private Map<String, Object> postJson(String url, Map<String, Object> body)
            throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new VersatileHttpException(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    }

    private String resolvePath(String path) {
        String base = properties.getBaseUrl();
        if (base.endsWith("/") && path.startsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    public static class VersatileHttpException extends IOException {
        private final int statusCode;
        private final String responseBody;

        public VersatileHttpException(int statusCode, String responseBody) {
            super("Versatile HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
