package com.openjiuwen.versatile_adapter.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.versatile_adapter.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.openjiuwen.core.common.security.SslUtils.createInsecureSslContext;

/**
 * VersatileProxy — 通过 HTTP 流式调用 Versatile 低代码平台（NDJSON/SSE 协议）。
 */
public class VersatileProxy {

    private static final Logger logger = LoggerFactory.getLogger(VersatileProxy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> FORWARD_HEADER_WHITELIST = Set.of(
            "x-user-id", "x-project-id", "cust-token", "cust-userid"
    );

    private final Config config;
    private final String urlTemplate;
    private final int timeout;

    public VersatileProxy(Config config) {
        this.config = config;
        this.urlTemplate = config.getVersatileUrlTemplate();
        this.timeout = config.getVersatileTimeout();
    }

    private String buildUrl(String convId) {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            return "";
        }
        if (urlTemplate.contains("{conversation_id}")) {
            return urlTemplate.replace("{conversation_id}", convId);
        }
        return urlTemplate;
    }

    private String buildUrl(String convId, Map<String, Object> params) {
        String url = buildUrl(convId);
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            query.append('=');
            query.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        if (query.length() == 0) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private boolean usesConversationStreamProtocol() {
        String value = urlTemplate != null ? urlTemplate : "";
        return value.contains("agentConversationStream.htm") || !value.contains("{conversation_id}");
    }

    private Map<String, Object> asMapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private Object nestedValue(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private String nestedString(Map<String, Object> source, String... path) {
        Object value = nestedValue(source, path);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String headerValue(Map<String, String> headers, String... candidates) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        for (String candidate : candidates) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(candidate)) {
                    return entry.getValue() != null ? entry.getValue().trim() : "";
                }
            }
        }
        return "";
    }

    private Map<String, Object> buildLegacyRequestBody(Map<String, Object> body) {
        return new LinkedHashMap<>(asMapOrEmpty(body.getOrDefault("custom_data", Map.of())));
    }

    private Map<String, Object> buildConversationStreamBody(
            Map<String, Object> body,
            String convId,
            Map<String, String> headers
    ) {
        String question = firstNonBlank(
                nestedString(body, "question"),
                nestedString(body, "input", "query"),
                nestedString(body, "custom_data", "inputs", "query"),
                nestedString(body, "custom_data", "query")
        );
        String agentName = firstNonBlank(
                nestedString(body, "agentName"),
                nestedString(body, "custom_data", "agentName"),
                nestedString(body, "custom_data", "inputs", "agentName"),
                nestedString(body, "input", "agentName"),
                nestedString(body, "custom_data", "inputs", "intent"),
                nestedString(body, "input", "intent")
        );
        String userId = firstNonBlank(
                nestedString(body, "userId"),
                nestedString(body, "custom_data", "userId"),
                nestedString(body, "custom_data", "inputs", "userId"),
                headerValue(headers, "x-user-id", "cust-userid")
        );
        String engine = firstNonBlank(
                nestedString(body, "engine"),
                nestedString(body, "custom_data", "engine"),
                nestedString(body, "custom_data", "inputs", "engine")
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("agentName", agentName);
        requestBody.put("question", question);
        requestBody.put("sessionId", convId != null ? convId : "");
        requestBody.put("userId", userId);
        requestBody.put("engine", engine);
        return requestBody;
    }

    private Map<String, Object> buildUpstreamRequestBody(
            Map<String, Object> body,
            String convId,
            Map<String, String> headers
    ) {
        if (usesConversationStreamProtocol()) {
            return buildConversationStreamBody(body, convId, headers);
        }
        return buildLegacyRequestBody(body);
    }

    private String toSnakeCase(String key) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0 && builder.charAt(builder.length() - 1) != '_') {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            normalized.put(toSnakeCase(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private void mergeMissing(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Object> normalizeConversationStreamData(String type, Map<String, Object> rawData) {
        Map<String, Object> normalized = normalizeKeys(rawData);
        Object content = rawData.get("content");
        if (content instanceof String textContent) {
            String trimmed = textContent.trim();
            if ("rawData".equals(type) && !trimmed.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(trimmed, Map.class);
                    normalized.put("raw_content", trimmed);
                    mergeMissing(normalized, normalizeKeys(parsed));
                } catch (Exception ignored) {
                    normalized.put("raw_content", trimmed);
                }
            }
            if (!trimmed.isEmpty()) {
                if (Set.of("text", "answer", "message", "bubble").contains(type) && !normalized.containsKey("text")) {
                    normalized.put("text", trimmed);
                }
                if ("dialogId".equals(type) && !normalized.containsKey("dialog_id")) {
                    normalized.put("dialog_id", trimmed);
                }
                if ("nodeType".equals(type) && !normalized.containsKey("node_type")) {
                    normalized.put("node_type", trimmed);
                }
                if ("nodeId".equals(type) && !normalized.containsKey("node_id")) {
                    normalized.put("node_id", trimmed);
                }
                normalized.putIfAbsent("content", trimmed);
            }
        }
        if (!normalized.containsKey("text")) {
            Object summary = normalized.get("summary");
            if (summary instanceof String summaryText && !summaryText.isBlank()) {
                normalized.put("text", summaryText);
            }
        }
        return normalized;
    }

    private Map<String, Object> unwrapLegacyFrame(Map<String, Object> outer) {
        if (outer == null) {
            return Map.of("event", "message", "data", Map.of());
        }
        Object customRspData = outer.get("custom_rsp_data");
        if (customRspData instanceof Map<?, ?> inner && inner.containsKey("event")) {
            return Map.of(
                    "event", String.valueOf(inner.get("event")),
                    "data", asMapOrEmpty(inner.get("data"))
            );
        }
        if (outer.containsKey("event")) {
            return Map.of(
                    "event", String.valueOf(outer.get("event")),
                    "data", asMapOrEmpty(outer.get("data"))
            );
        }
        return Map.of("event", "message", "data", outer);
    }

    private Map<String, Object> normalizeUpstreamFrame(Map<String, Object> outer) {
        if (outer == null) {
            return Map.of("event", "message", "data", Map.of());
        }
        Object type = outer.get("type");
        Object data = outer.get("data");
        if (type != null && data instanceof Map<?, ?> rawData) {
            return Map.of(
                    "event", String.valueOf(type),
                    "data", normalizeConversationStreamData(String.valueOf(type), asMapOrEmpty(rawData))
            );
        }
        return unwrapLegacyFrame(outer);
    }

    public void dispatchStream(
            Map<String, Object> body,
            String convId,
            Map<String, String> extraHeaders,
            Consumer<Map<String, Object>> chunkConsumer
    ) {
        dispatchStream(body, convId, extraHeaders, Map.of(), chunkConsumer);
    }

    public void dispatchStream(
            Map<String, Object> body,
            String convId,
            Map<String, String> extraHeaders,
            Map<String, Object> params,
            Consumer<Map<String, Object>> chunkConsumer
    ) {
        String url = buildUrl(convId, params);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .sslContext(createInsecureSslContext())
                    .sslParameters(new javax.net.ssl.SSLParameters() {{
                        setEndpointIdentificationAlgorithm("");
                    }})
                    .build();

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (entry.getKey() != null
                            && FORWARD_HEADER_WHITELIST.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                        headers.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            Map<String, Object> requestBody = buildUpstreamRequestBody(body, convId, headers);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            logger.info("[VersatileProxy] POST {}", url);
            logger.debug("[VersatileProxy] protocol={} headers={} body={} params={}",
                    usesConversationStreamProtocol() ? "conversation_stream" : "legacy",
                    headers, jsonBody, params);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!"content-type".equalsIgnoreCase(header.getKey())) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpResponse<java.util.stream.Stream<String>> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofLines()
            );

            if (response.statusCode() >= 400) {
                StringBuilder errorBody = new StringBuilder();
                response.body().forEach(line -> errorBody.append(line).append("\n"));
                logger.error("[VersatileProxy] HTTP error code:{} url:{} body:{}",
                        response.statusCode(), url, errorBody);
                return;
            }

            response.body().forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    return;
                }
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }
                if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                    return;
                }

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outer = objectMapper.readValue(trimmed, Map.class);
                    Map<String, Object> chunkMap = normalizeUpstreamFrame(outer);
                    logger.debug("[VersatileProxy] received chunk: {}", chunkMap);
                    chunkConsumer.accept(chunkMap);
                } catch (Exception e) {
                    logger.warn("[VersatileProxy] unable to parse line: {}",
                            trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed, e);
                }
            });

        } catch (Exception e) {
            logger.error("[VersatileProxy] request failed: {}", e.getMessage(), e);
        }
    }
}
