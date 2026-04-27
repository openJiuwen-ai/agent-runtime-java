package com.openjiuwen.versatile_adapter.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * VersatileProxy — 通过 HTTP 流式调用 Versatile 低代码平台（NDJSON/SSE 协议）。
 *
 * 对应 Python: adapter/versatile_proxy.py
 *
 * Constructor: urlTemplate, timeout=600
 * dispatchStream(body, convId, extraHeaders): POST 到 urlTemplate.format(convId), 流式读取响应
 * 转发白名单请求头: x-user-id, x-project-id, cust-token, cust-userid
 * 解析 SSE 行，通过 Consumer 回调每个 chunk
 */
public class VersatileProxy {

    private static final Logger logger = LoggerFactory.getLogger(VersatileProxy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> FORWARD_HEADER_WHITELIST = Set.of(
            "x-user-id", "x-project-id", "cust-token", "cust-userid"
    );

    private final String urlTemplate;
    private final int timeout;

    public VersatileProxy(String urlTemplate, int timeout) {
        this.urlTemplate = urlTemplate;
        this.timeout = timeout;
    }

    private String buildUrl(String convId) {
        return urlTemplate.replace("{conversation_id}", convId);
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

    private Map<String, Object> unwrapUpstreamFrame(Map<String, Object> outer) {
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

    private Map<String, Object> asMapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    /**
     * 流式分发请求到 Versatile 平台。
     *
     * POST 请求体（custom_data）到 urlTemplate.format(convId)，逐行解析 SSE/NDJSON 响应。
     * 每个解析后的 chunk 会通过 chunkConsumer 回调。
     *
     * @param body          请求体（custom_data）
     * @param convId        会话 ID，用于替换 URL 模板中的 {conversation_id}
     * @param extraHeaders  额外请求头（仅转发白名单中的 header）
     * @param chunkConsumer 每个 chunk 的消费者
     */
    @SuppressWarnings("unchecked")
    public void dispatchStream(Map<String, Object> body, String convId,
                               Map<String, String> extraHeaders,
                               Consumer<Map<String, Object>> chunkConsumer) {
        dispatchStream(body, convId, extraHeaders, Map.of(), chunkConsumer);
    }

    /**
     * 流式分发请求到 Versatile 平台，并透传 URL query params。
     */
    @SuppressWarnings("unchecked")
    public void dispatchStream(Map<String, Object> body, String convId,
                               Map<String, String> extraHeaders,
                               Map<String, Object> params,
                               Consumer<Map<String, Object>> chunkConsumer) {
        String url = buildUrl(convId, params);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();

            // 构建 headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (FORWARD_HEADER_WHITELIST.contains(entry.getKey().toLowerCase())) {
                        headers.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            // Python 发送 body.get("custom_data", {})，而非整个 body
            Object customData = body.getOrDefault("custom_data", new HashMap<>());
            String jsonBody = objectMapper.writeValueAsString(customData);

            logger.info("[VersatileProxy] POST {}", url);
            logger.debug("[VersatileProxy] 请求头: {}", headers);
            logger.debug("[VersatileProxy] 请求体: {}", jsonBody);
            logger.debug("[VersatileProxy] 请求参数: {}", params);

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
                logger.error("[VersatileProxy] HTTP 错误code:{} url:{} body:{}", response.statusCode(), url, errorBody);
                return;
            }

            response.body().forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    return;
                }

                // SSE 格式：去掉 "data:" 前缀
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }
                if (trimmed.isEmpty()) {
                    return;
                }

                try {
                    Map<String, Object> outer = objectMapper.readValue(trimmed, Map.class);
                    Map<String, Object> chunkMap = unwrapUpstreamFrame(outer);
                    logger.debug("[VersatileProxy] received chunk: {}", chunkMap);
                    chunkConsumer.accept(chunkMap);
                } catch (Exception e) {
                    logger.warn("[VersatileProxy] 无法解析行: {}",
                            trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed, e);
                }
            });

        } catch (Exception e) {
            logger.error("[VersatileProxy] 请求错误: {}", e.getMessage(), e);
        }
    }
}
