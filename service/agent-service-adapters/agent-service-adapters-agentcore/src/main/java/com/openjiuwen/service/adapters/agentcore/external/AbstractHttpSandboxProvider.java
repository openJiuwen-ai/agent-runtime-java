/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.config.SandboxLauncherConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxEndpoint;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base HTTP implementation for sandbox operation providers.
 *
 * @since 2026-06-24
 */
abstract class AbstractHttpSandboxProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_INVOKE_PATH = "/invoke";

    private final SandboxEndpoint endpoint;
    private final SandboxGatewayConfig config;
    private final HttpClient httpClient;

    AbstractHttpSandboxProvider(SandboxEndpoint endpoint, SandboxGatewayConfig config) {
        this.endpoint = endpoint != null ? endpoint : SandboxEndpoint.builder().build();
        this.config = config != null ? config : SandboxGatewayConfig.builder().build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .build();
    }

    final <T> T invoke(String opType, String method, Map<String, Object> params, Class<T> resultType) {
        String body = postInvoke(opType, method, params);
        try {
            return MAPPER.readValue(body, resultType);
        } catch (IOException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Failed to decode sandbox response, method=" + opType + "." + method,
                    ex);
        }
    }

    final <T> Iterator<T> invokeStream(
            String opType,
            String method,
            Map<String, Object> params,
            Class<T> elementType) {
        String body = postInvoke(opType, method, params);
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode streamNode = streamNode(root);
            List<T> results = new ArrayList<>();
            if (streamNode.isArray()) {
                for (JsonNode item : streamNode) {
                    results.add(MAPPER.treeToValue(item, elementType));
                }
                return results.iterator();
            }
            results.add(MAPPER.treeToValue(streamNode, elementType));
            return results.iterator();
        } catch (IOException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Failed to decode sandbox stream response, method=" + opType + "." + method,
                    ex);
        }
    }

    final Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return params;
    }

    private String postInvoke(String opType, String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("opType", opType);
        request.put("method", method);
        request.put("params", params != null ? params : Map.of());
        request.put("isolationKey", endpoint.getSandboxId());
        request.put("sandboxId", endpoint.getSandboxId());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(invokeUri())
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8));
        authHeaders().forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                requestBuilder.header(name, value);
            }
        });

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ExternalSvcAdapterException(
                        ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                        "Sandbox HTTP call failed, status=" + response.statusCode()
                                + ", method=" + opType + "." + method);
            }
            return response.body();
        } catch (IOException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Sandbox HTTP call failed, method=" + opType + "." + method,
                    ex);
        } catch (InterruptedException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_RETRY_INTERRUPTED,
                    "Sandbox HTTP call interrupted, method=" + opType + "." + method,
                    ex);
        }
    }

    private String toJson(Map<String, Object> request) {
        try {
            return MAPPER.writeValueAsString(request);
        } catch (IOException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Failed to encode sandbox request",
                    ex);
        }
    }

    private URI invokeUri() {
        Optional<String> baseUrl = firstText(
                endpoint.getBaseUrl(),
                config.getGatewayUrl(),
                launcherBaseUrl().orElse(null));
        if (baseUrl.isEmpty()) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Sandbox service URL is empty");
        }
        URI baseUri = URI.create(baseUrl.get());
        Optional<String> configuredPath = configuredInvokePath();
        String path = resolvePath(baseUri.getPath(), configuredPath);
        Optional<String> query = appendAuthQuery(baseUri.getRawQuery());
        try {
            return new URI(baseUri.getScheme(), baseUri.getUserInfo(), baseUri.getHost(), baseUri.getPort(),
                    path, query.orElse(null), null);
        } catch (URISyntaxException ex) {
            throw new ExternalSvcAdapterException(
                    ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED,
                    "Invalid sandbox service URL: " + baseUrl.get(),
                    ex);
        }
    }

    private Optional<String> launcherBaseUrl() {
        SandboxLauncherConfig launcherConfig = config.getLauncherConfig();
        if (launcherConfig == null) {
            return Optional.empty();
        }
        return firstText(launcherConfig.getBaseUrl(), launcherConfig.getGatewayUrl());
    }

    private Optional<String> configuredInvokePath() {
        Optional<Object> value = valueFrom(config.getParams(), "invoke_path", "invokePath");
        if (value.isPresent()) {
            return Optional.of(String.valueOf(value.get()));
        }
        SandboxLauncherConfig launcherConfig = config.getLauncherConfig();
        return launcherConfig != null
                ? stringValue(valueFrom(launcherConfig.getExtraParams(), "invoke_path", "invokePath"))
                : Optional.empty();
    }

    private String resolvePath(String basePath, Optional<String> configuredPath) {
        String normalizedBasePath = basePath != null ? basePath : "";
        if (configuredPath.isEmpty() || configuredPath.get().isBlank()) {
            if (!normalizedBasePath.isBlank() && !"/".equals(normalizedBasePath)) {
                return normalizedBasePath;
            }
            return DEFAULT_INVOKE_PATH;
        }
        String configuredPathValue = configuredPath.get();
        String nextPath = configuredPathValue.startsWith("/") ? configuredPathValue : "/" + configuredPathValue;
        if (normalizedBasePath.isBlank() || "/".equals(normalizedBasePath)) {
            return nextPath;
        }
        return trimTrailingSlash(normalizedBasePath) + nextPath;
    }

    private Optional<String> appendAuthQuery(String existingQuery) {
        StringBuilder query = new StringBuilder(existingQuery != null ? existingQuery : "");
        authQueryParams().forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                return;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(name)).append('=').append(encode(value));
        });
        return query.length() > 0 ? Optional.of(query.toString()) : Optional.empty();
    }

    private JsonNode streamNode(JsonNode root) {
        if (root.has("results")) {
            return root.get("results");
        }
        if (root.has("items")) {
            return root.get("items");
        }
        return root;
    }

    private int timeoutSeconds() {
        return Math.max(1, config.getTimeoutSeconds());
    }

    private Map<String, String> authHeaders() {
        return config.getAuthHeaders() != null ? config.getAuthHeaders() : Map.of();
    }

    private Map<String, String> authQueryParams() {
        return config.getAuthQueryParams() != null ? config.getAuthQueryParams() : Map.of();
    }

    private static Optional<Object> valueFrom(Map<String, ?> values, String... keys) {
        if (values == null) {
            return Optional.empty();
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> stringValue(Optional<Object> value) {
        return value.map(String::valueOf);
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
