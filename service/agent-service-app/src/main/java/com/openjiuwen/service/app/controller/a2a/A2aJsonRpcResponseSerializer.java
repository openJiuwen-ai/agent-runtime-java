/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AMessage;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.StreamingEventKind;

/**
 * Serializes A2A success responses for every Runtime ingress transport.
 *
 * <p>The HTTP controller and non-HTTP adapters must use this class rather than independently
 * rebuilding JSON-RPC envelopes. That keeps their externally observable responses identical.
 *
 * @since 0.1.0
 */
public final class A2aJsonRpcResponseSerializer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private A2aJsonRpcResponseSerializer() {
    }

    /**
     * Serializes a {@code SendMessage} result exactly as the HTTP A2A endpoint does.
     *
     * @param requestId JSON-RPC request identity
     * @param result standard A2A result union
     * @return complete JSON-RPC response
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException on serialization failure
     */
    public static String sendMessage(Object requestId, EventKind result)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return serialize(new SendMessageResponse(requestId, result));
    }

    /**
     * Serializes one event from {@code SendStreamingMessage} or {@code SubscribeToTask}.
     *
     * @param requestId JSON-RPC request identity
     * @param event standard A2A streaming event
     * @return complete JSON-RPC response frame
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException on serialization failure
     */
    public static String streamingEvent(Object requestId, StreamingEventKind event)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return "{\"jsonrpc\":\"" + A2AMessage.JSONRPC_VERSION + "\",\"id\":" + GSON.toJson(requestId)
                + ",\"result\":" + serialize(event) + "}";
    }

    /**
     * Serializes a {@code GetTask} success response.
     *
     * @param requestId JSON-RPC request identity
     * @param result Task result returned by the handler
     * @return complete JSON-RPC response
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException on serialization failure
     */
    public static String queryResult(Object requestId, Object result)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        JsonElement resultElement = JsonParser.parseString(JsonUtil.toJson(result));
        JsonObject object = resultElement.getAsJsonObject();
        if (object.size() == 1) {
            String key = object.keySet().iterator().next();
            if ("task".equals(key) || "message".equals(key) || "statusUpdate".equals(key)
                    || "artifactUpdate".equals(key)) {
                resultElement = object.get(key);
            }
        }
        String idPart = requestId != null ? ",\"id\":" + GSON.toJson(requestId) : "";
        return "{\"jsonrpc\":\"2.0\"" + idPart + ",\"result\":" + GSON.toJson(resultElement) + "}";
    }

    /**
     * Serializes an A2A SDK value without HTML-safe Unicode escaping.
     *
     * @param value SDK value
     * @return JSON representation
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException on serialization failure
     */
    public static String serialize(Object value)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return GSON.toJson(JsonParser.parseString(JsonUtil.toJson(value)));
    }
}
