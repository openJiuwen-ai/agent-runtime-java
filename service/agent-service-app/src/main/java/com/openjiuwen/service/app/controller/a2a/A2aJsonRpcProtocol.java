/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AMessage;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Adapts the A2A SDK JSON-RPC model to the HTTP binding used by the service.
 */
final class A2aJsonRpcProtocol {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcProtocol.class);

    private static final Gson GSON = new Gson();

    private A2aJsonRpcProtocol() {
    }

    static Request parseRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new RequestException(null, new JSONParseError("Parse error"));
        }

        JsonElement document;
        try {
            document = JsonParser.parseString(rawBody);
        } catch (JsonSyntaxException e) {
            throw new RequestException(null, new JSONParseError("Parse error"));
        }

        if (!document.isJsonObject()) {
            throw new RequestException(null, new InvalidRequestError("Invalid Request"));
        }

        JsonObject payload = document.getAsJsonObject();
        Object id = hasValidId(payload) && payload.has("id") ? GSON.fromJson(payload.get("id"), Object.class) : null;
        if (!isValidRequest(payload)) {
            throw new RequestException(id, new InvalidRequestError("Invalid Request"));
        }
        return new Request(payload, payload.get("method").getAsString(), id);
    }

    static ResponseEntity<String> errorResponse(Object id, A2AError error) {
        try {
            JsonObject response = JsonParser.parseString(JsonUtil.toJson(new A2AErrorResponse(id, error)))
                    .getAsJsonObject();
            if (!response.has("id")) {
                response.add("id", JsonNull.INSTANCE);
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.toString());
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("Failed to serialize A2A JSON-RPC error response", e);
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body(internalErrorResponse());
        }
    }

    private static boolean isValidRequest(JsonObject request) {
        if (!hasStringValue(request, "jsonrpc")
                || !A2AMessage.JSONRPC_VERSION.equals(request.get("jsonrpc").getAsString())) {
            return false;
        }
        if (!hasStringValue(request, "method") || request.get("method").getAsString().isBlank()) {
            return false;
        }
        if (!hasValidId(request)) {
            return false;
        }
        return !request.has("params") || request.get("params").isJsonObject() || request.get("params").isJsonArray();
    }

    private static boolean hasStringValue(JsonObject request, String memberName) {
        JsonElement value = request.get(memberName);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean hasValidId(JsonObject request) {
        JsonElement id = request.get("id");
        return id == null || id.isJsonNull()
                || (id.isJsonPrimitive() && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber()));
    }

    private static String internalErrorResponse() {
        InternalError internalError = new InternalError("Internal error");
        JsonObject error = new JsonObject();
        error.addProperty("code", internalError.getCode());
        error.addProperty("message", internalError.getMessage());

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", A2AMessage.JSONRPC_VERSION);
        response.add("id", JsonNull.INSTANCE);
        response.add("error", error);
        return response.toString();
    }

    record Request(JsonObject payload, String method, Object id) {
    }

    static final class RequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Object requestId;

        private final transient A2AError error;

        RequestException(Object requestId, A2AError error) {
            super(error.getMessage(), error);
            this.requestId = requestId;
            this.error = error;
        }

        Object getRequestId() {
            return requestId;
        }

        A2AError getError() {
            return error;
        }
    }
}
