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

/**
 * Fixed receiver for runtime-to-runtime A2A push notification callbacks.
 */
@RestController
public class A2aPushNotificationCallbackController {
    private static final String NOTIFICATION_ID_HEADER = "X-A2A-Notification-Id";

    private final A2aPushNotificationCallbackStore callbackStore;

    private final A2aPushNotificationCallbackHandler callbackHandler;

    public A2aPushNotificationCallbackController(A2aPushNotificationCallbackStore callbackStore,
            A2aPushNotificationCallbackHandler callbackHandler) {
        this.callbackStore = callbackStore;
        this.callbackHandler = callbackHandler;
    }

    @PostMapping(value = A2AServicePaths.A2A_PUSH_NOTIFICATION_CALLBACK, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "a2a-push-callback", action = "receive")
    public ResponseEntity<String> handleCallback(@RequestBody(required = false) String rawBody,
            jakarta.servlet.http.HttpServletRequest request) {
        JsonObject body;
        try {
            body = JsonParser.parseString(rawBody == null ? "" : rawBody).getAsJsonObject();
        } catch (RuntimeException e) {
            return badRequest("callback body must be a JSON object");
        }
        String headerNotificationId = trimToNull(request.getHeader(NOTIFICATION_ID_HEADER));
        String bodyNotificationId = stringMember(body, "notificationId");
        String notificationId = headerNotificationId != null ? headerNotificationId : bodyNotificationId;
        if (notificationId == null) {
            return badRequest("notificationId is required");
        }
        if (headerNotificationId != null && bodyNotificationId != null && !headerNotificationId.equals(
                bodyNotificationId)) {
            return badRequest("notificationId mismatch between header and body");
        }
        if (!isJsonRpcResult(body)) {
            return badRequest("callback body must contain a JSON-RPC result");
        }
        Task task;
        try {
            task = callbackTask(body);
        } catch (RuntimeException e) {
            return badRequest("callback result.task is required");
        }
        A2aPushNotificationCallbackStore.SaveResult result = callbackStore.saveIfAbsent(notificationId,
                sha256(body.toString()));
        if (result == A2aPushNotificationCallbackStore.SaveResult.CONFLICT) {
            return status(HttpStatus.CONFLICT, "conflict", notificationId);
        }
        if (result == A2aPushNotificationCallbackStore.SaveResult.CREATED) {
            callbackHandler.onAccepted(new A2aPushNotificationCallback(notificationId, task));
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
            parsed = JsonUtil.fromJson(task.toString(), Task.class);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            throw new IllegalArgumentException("callback result.task is invalid", e);
        }
        if (parsed == null || parsed.id() == null || parsed.id().isBlank()) {
            throw new IllegalArgumentException("callback result.task.id is required");
        }
        return parsed;
    }

    private static String stringMember(JsonObject body, String memberName) {
        JsonElement value = body.get(memberName);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return trimToNull(value.getAsString());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
