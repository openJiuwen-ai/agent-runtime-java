/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed receiver for runtime-to-runtime A2A push notification callbacks.
 *
 * @since 0.1.0
 */
@RestController
public class A2aPushNotificationCallbackController {
    private static final String NOTIFICATION_ID_HEADER = "X-A2A-Notification-Id";

    private final A2aPushNotificationCallbackStore callbackStore;

    private final A2aPushNotificationCallbackHandler callbackHandler;

    private final A2aPushNotificationCapabilityGate capabilityGate;

    public A2aPushNotificationCallbackController(A2aPushNotificationCallbackStore callbackStore,
            A2aPushNotificationCallbackHandler callbackHandler, A2aPushNotificationCapabilityGate capabilityGate) {
        this.callbackStore = callbackStore;
        this.callbackHandler = callbackHandler;
        this.capabilityGate = capabilityGate;
    }

    /**
     * Receives and deduplicates runtime-to-runtime push notification callbacks.
     *
     * @param rawBody the raw JSON callback body
     * @param request the HTTP servlet request
     * @return the callback acceptance response
     */
    @PostMapping(value = A2AServicePaths.A2A_PUSH_NOTIFICATION_CALLBACK, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "a2a-push-callback", action = "receive")
    public ResponseEntity<String> handleCallback(@RequestBody(required = false) String rawBody,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!capabilityGate.isPushNotificationsEnabled()) {
            return status(HttpStatus.NOT_IMPLEMENTED, "push notification callback is not enabled", null);
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(rawBody == null ? "" : rawBody).getAsJsonObject();
        } catch (IllegalStateException | com.google.gson.JsonParseException e) {
            return badRequest("callback body must be a JSON object");
        }
        Optional<String> headerNotificationId = trimToEmpty(request.getHeader(NOTIFICATION_ID_HEADER));
        Optional<String> bodyNotificationId = stringMember(body, "notificationId");
        String notificationId = headerNotificationId.or(() -> bodyNotificationId).orElse("");
        if (notificationId == null) {
            return badRequest("notificationId is required");
        }
        if (notificationId.isBlank()) {
            return badRequest("notificationId is required");
        }
        if (headerNotificationId.isPresent() && bodyNotificationId.isPresent()
                && !headerNotificationId.get().equals(bodyNotificationId.get())) {
            return badRequest("notificationId mismatch between header and body");
        }
        if (!isJsonRpcResult(body)) {
            return badRequest("callback body must contain a JSON-RPC result");
        }
        Task task;
        try {
            task = callbackTask(body);
        } catch (IllegalArgumentException e) {
            return badRequest("callback result.task is required");
        }
        A2aPushNotificationCallbackStore.SaveResult result = callbackStore.saveIfAbsent(notificationId,
                sha256(body.toString()));
        if (result == A2aPushNotificationCallbackStore.SaveResult.CONFLICT) {
            return status(HttpStatus.CONFLICT, "conflict", notificationId);
        }
        if (result == A2aPushNotificationCallbackStore.SaveResult.CREATED) {
            boolean isHandled = callbackHandler.onAccepted(new A2aPushNotificationCallback(notificationId, task));
            if (!isHandled) {
                return status(HttpStatus.NOT_FOUND, "callback binding not found", notificationId);
            }
        }
        return status(HttpStatus.OK, "accepted", notificationId);
    }

    private static boolean isJsonRpcResult(JsonObject body) {
        JsonElement version = body.get("jsonrpc");
        return version != null && version.isJsonPrimitive() && "2.0".equals(version.getAsString())
                && body.get("result") != null && body.get("result").isJsonObject();
    }

    private static Task callbackTask(JsonObject body) {
        JsonElement task = body.getAsJsonObject("result").get("task");
        if (task == null || !task.isJsonObject()) {
            throw new IllegalArgumentException("callback result.task is required");
        }
        Task parsed;
        try {
            parsed = normalizeState(JsonUtil.fromJson(task.toString(), Task.class), task.getAsJsonObject());
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            throw new IllegalArgumentException("callback result.task is invalid", e);
        }
        if (parsed == null || parsed.id() == null || parsed.id().isBlank()) {
            throw new IllegalArgumentException("callback result.task.id is required");
        }
        return parsed;
    }

    private static Task normalizeState(Task task, JsonObject rawTask) {
        if (task == null || task.status() == null
                || (task.status().state() != null && task.status().state() != TaskState.UNRECOGNIZED)) {
            return task;
        }
        return rawState(rawTask)
                .map(state -> new TaskStatus(state, task.status().message(), task.status().timestamp()))
                .map(status -> Task.builder(task).status(status).build())
                .orElse(task);
    }

    private static Optional<TaskState> rawState(JsonObject rawTask) {
        Optional<JsonElement> state = findState(rawTask);
        if (state.isEmpty() || !state.get().isJsonPrimitive()) {
            return Optional.empty();
        }
        String value = state.get().getAsString();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        if (!normalized.startsWith("TASK_STATE_")) {
            normalized = "TASK_STATE_" + normalized;
        }
        try {
            return Optional.of(TaskState.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonElement> findState(JsonObject object) {
        if (object == null) {
            return Optional.empty();
        }
        JsonElement direct = object.get("state");
        if (direct != null) {
            return Optional.of(direct);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                Optional<JsonElement> nested = findState(value.getAsJsonObject());
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> stringMember(JsonObject body, String memberName) {
        JsonElement value = body.get(memberName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        return trimToEmpty(value.getAsString());
    }

    private static Optional<String> trimToEmpty(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static ResponseEntity<String> badRequest(String message) {
        return status(HttpStatus.BAD_REQUEST, message, null);
    }

    private static ResponseEntity<String> status(HttpStatus status, String message, String notificationId) {
        String notificationPart = notificationId == null ? "" : ",\"notificationId\":\"" + notificationId + "\"";
        String key = status.is2xxSuccessful() ? "status" : "error";
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"" + key + "\":\"" + message + "\"" + notificationPart + "}");
    }
}
