/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.AuthenticationInfo;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sends A2A task terminal notifications to a trusted callback receiver.
 *
 * @since 0.1.0
 */
public class HttpPushNotificationSender implements PushNotificationSender {
    private static final Logger log = LoggerFactory.getLogger(HttpPushNotificationSender.class);

    private final PushNotificationConfigStore configStore;

    private final A2AProperties.PushNotificationProperties properties;

    private final HttpClient httpClient;

    private final ConcurrentMap<String, DeliveryRecord> deliveryRecords = new ConcurrentHashMap<>();

    public HttpPushNotificationSender(PushNotificationConfigStore configStore,
            A2AProperties.PushNotificationProperties properties) {
        this(configStore, properties, HttpClient.newHttpClient());
    }

    HttpPushNotificationSender(PushNotificationConfigStore configStore,
            A2AProperties.PushNotificationProperties properties, HttpClient httpClient) {
        this.configStore = configStore;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public void sendNotification(StreamingEventKind event, Task task) {
        if (task == null || task.id() == null) {
            return;
        }
        Optional<TaskPushNotificationConfig> config = firstConfig(task.id());
        if (config.isEmpty() || config.get().url() == null || config.get().url().isBlank()) {
            return;
        }
        Optional<URI> callbackUri = A2aPushNotificationTrustPolicy.trustedCallbackUri(config.get().url(), properties);
        String notificationId = notificationId(task.id(), config.get().id(), event == null ? null : event.kind());
        if (callbackUri.isEmpty()) {
            record(notificationId, task.id(), config.get().id(), false, "untrusted callback host");
            log.warn("Rejected A2A push notification for untrusted callback URL {}", config.get().url());
            return;
        }
        HttpRequest request = request(callbackUri.get(), notificationId, config.get(), callbackBody(notificationId,
                task));
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean isSuccess = response.statusCode() >= 200 && response.statusCode() < 300;
            record(notificationId, task.id(), config.get().id(), isSuccess, "HTTP " + response.statusCode());
        } catch (IOException e) {
            record(notificationId, task.id(), config.get().id(), false, e.getClass().getSimpleName());
            log.warn("A2A push notification delivery failed for task {}", task.id(), e);
        } catch (InterruptedException e) {
            record(notificationId, task.id(), config.get().id(), false, "interrupted");
        }
    }

    Optional<DeliveryRecord> deliveryRecord(String notificationId) {
        return Optional.ofNullable(deliveryRecords.get(notificationId));
    }

    String notificationIdFor(Task task, TaskPushNotificationConfig config, StreamingEventKind event) {
        return notificationId(task.id(), config.id(), event == null ? null : event.kind());
    }

    private Optional<TaskPushNotificationConfig> firstConfig(String taskId) {
        ListTaskPushNotificationConfigsParams params = new ListTaskPushNotificationConfigsParams(taskId);
        List<TaskPushNotificationConfig> configs = configStore.getInfo(params).configs();
        return configs == null || configs.isEmpty() ? Optional.empty() : Optional.of(configs.get(0));
    }

    private HttpRequest request(URI callbackUri, String notificationId, TaskPushNotificationConfig config,
            String body) {
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(callbackUri).POST(publisher)
                .header("Content-Type", "application/json")
                .header("X-A2A-Notification-Id", notificationId);
        authorizationHeader(config).ifPresent(value -> builder.header("Authorization", value));
        return builder.build();
    }

    private Optional<String> authorizationHeader(TaskPushNotificationConfig config) {
        if (config.token() != null && !config.token().isBlank()) {
            return Optional.of("Bearer " + config.token());
        }
        AuthenticationInfo authentication = config.authentication();
        if (authentication == null || authentication.scheme() == null || authentication.credentials() == null) {
            return Optional.empty();
        }
        if ("Bearer".equalsIgnoreCase(authentication.scheme())) {
            return Optional.of("Bearer " + authentication.credentials());
        }
        return Optional.of(authentication.scheme() + " " + authentication.credentials());
    }

    String callbackBody(String notificationId, Task task) {
        try {
            return JsonUtil.toJson(Map.of("jsonrpc", "2.0", "result", task, "notificationId", notificationId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize A2A callback body", e);
        }
    }

    private String notificationId(String taskId, String configId, String eventKind) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String configPart = configId == null ? "" : configId;
            String eventPart = eventKind == null ? "" : eventKind;
            String source = taskId + ":" + configPart + ":" + eventPart;
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void record(String notificationId, String taskId, String configId, boolean isSuccess, String message) {
        deliveryRecords.compute(notificationId, (key, previous) -> {
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            return new DeliveryRecord(notificationId, taskId, configId, attempts, isSuccess, message, Instant.now());
        });
    }

    record DeliveryRecord(String notificationId, String taskId, String configId, int attempts, boolean isSuccess,
            String message, Instant updatedAt) {
    }
}
