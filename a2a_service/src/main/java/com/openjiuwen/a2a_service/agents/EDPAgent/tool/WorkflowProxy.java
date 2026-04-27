package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.EdpAgentSettings;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowProxy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Map<String, Object>> LAST_RESULTS = new ConcurrentHashMap<String, Map<String, Object>>();

    private WorkflowProxy() {
    }

    public static String getAdapterUrl() {
        String url = EdpAgentSettings.load(null, System.getenv()).getVersatileAdapterUrl();
        if (url == null || url.isBlank()) {
            url = "http://localhost:8091";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static double getTimeoutSeconds() {
        return EdpAgentSettings.load(null, System.getenv()).getVersatileTimeout();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getCustomDataFromSession(Session session) {
        if (session == null) {
            return Map.of();
        }
        Object originalBody = session.getState("original_body");
        if (!(originalBody instanceof Map<?, ?> body)) {
            return Map.of();
        }
        Object customData = body.get("custom_data");
        if (!(customData instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    public static String getConversationIdFromSession(Session session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return "default_conversation";
        }
        return session.getSessionId();
    }

    public static Map<String, Object> callWorkflow(
            String query,
            String conversationId,
            String intent,
            Session session
    ) throws Exception {
        Map<String, Object> customData = new LinkedHashMap<String, Object>(getCustomDataFromSession(session));
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("query", query);
        if (intent != null && !intent.isBlank()) {
            inputs.put("intent", intent);
        }

        if (customData.containsKey("inputs") && customData.get("inputs") instanceof Map<?, ?> map) {
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            merged.putAll(inputs);
            customData.put("inputs", merged);
        } else {
            customData.put("inputs", inputs);
        }

        Map<String, Object> requestBody = Map.of(
                "input", inputs,
                "stream", Boolean.TRUE,
                "custom_data", customData
        );

        String messageId = UUID.randomUUID().toString();
        Map<String, Object> jsonRpcRequest = Map.of(
                "jsonrpc", "2.0",
                "method", "SendMessage",
                "id", messageId,
                "params", Map.of(
                        "message", Map.of(
                                "message_id", messageId,
                                "role", 1,
                                "context_id", conversationId,
                                "parts", new Object[]{
                                        Map.of("text", query),
                                        Map.of("data", Map.of("body", requestBody))
                                }
                        )
                )
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis((long) (getTimeoutSeconds() * 1000)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getAdapterUrl() + "/"))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .timeout(Duration.ofMillis((long) (getTimeoutSeconds() * 1000)))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(jsonRpcRequest)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Adapter returned status " + response.statusCode());
        }

        Map<String, Object> businessData = new LinkedHashMap<String, Object>();
        boolean endSeen = false;
        BufferedReader reader = new BufferedReader(new StringReader(response.body()));
        String line;
        while ((line = reader.readLine()) != null) {
            String row = line.trim();
            if (!row.startsWith("data:")) {
                continue;
            }
            String raw = row.substring(5).trim();
            if (raw.isEmpty() || "[DONE]".equals(raw)) {
                continue;
            }
            Map<String, Object> eventObj;
            try {
                eventObj = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                continue;
            }
            Object artifactObj = eventObj.get("artifact");
            if (!(artifactObj instanceof Map<?, ?> artifactMap)) {
                continue;
            }
            Object partsObj = artifactMap.get("parts");
            if (!(partsObj instanceof Iterable<?> parts)) {
                continue;
            }
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> partMap) || !partMap.containsKey("data")) {
                    continue;
                }
                Object chunkObj = partMap.get("data");
                if (!(chunkObj instanceof Map<?, ?> chunkMap)) {
                    continue;
                }
                Object nodeType = chunkMap.get("node_type");
                if ("end".equalsIgnoreCase(String.valueOf(nodeType))) {
                    endSeen = true;
                }
                Map<String, Object> candidate = extractBusinessData(chunkMap);
                if (candidate != null) {
                    businessData = candidate;
                }
            }
        }
        return Map.of("business_data", businessData, "end_seen", endSeen);
    }

    public static void saveLastResult(String conversationId, String toolName, Map<String, Object> result) {
        LAST_RESULTS.put(conversationId + "::" + toolName, result);
    }

    public static Map<String, Object> popLastResult(String conversationId, String toolName) {
        return LAST_RESULTS.remove(conversationId + "::" + toolName);
    }

    public static Optional<Map<String, Object>> getLastResult(String conversationId, String toolName) {
        return Optional.ofNullable(LAST_RESULTS.get(conversationId + "::" + toolName));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractBusinessData(Map<?, ?> chunk) {
        Object data = chunk.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object text = dataMap.get("text");
            if (text instanceof String textValue) {
                try {
                    return MAPPER.readValue(textValue, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception ignored) {
                }
            }
            if (dataMap.containsKey("bankCardBalanceList")
                    || dataMap.containsKey("responseData")
                    || dataMap.containsKey("productList")) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        }
        if (chunk.containsKey("bankCardBalanceList")
                || chunk.containsKey("responseData")
                || chunk.containsKey("productList")) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : chunk.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return null;
    }
}
