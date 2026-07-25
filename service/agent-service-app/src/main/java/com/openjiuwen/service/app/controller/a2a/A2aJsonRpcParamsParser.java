/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AuthenticationInfo;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses and validates method-specific A2A JSON-RPC parameters before they are passed to the SDK.
 */
final class A2aJsonRpcParamsParser {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcParamsParser.class);

    private static final Gson GSON = new Gson();

    private static final Type METADATA_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private A2aJsonRpcParamsParser() {
    }

    static MessageSendParams parseMessageSendParams(JsonObject request) {
        try {
            JsonObject params = requiredObject(request, "params", "params");
            JsonObject messageObject = requiredObject(params, "message", "params.message");
            JsonArray partObjects = requiredNonEmptyArray(messageObject, "parts", "params.message.parts");
            List<Part<?>> parts = parseParts(partObjects);
            if (parts.isEmpty()) {
                throw invalid("params.message.parts must contain at least one non-blank text part");
            }
            Message message = buildMessage(messageObject, parts);
            MessageSendConfiguration configuration = parseConfiguration(params);

            return MessageSendParams.builder().message(message).configuration(configuration)
                    .metadata(parseMetadata(params, "params.metadata")).build();
        } catch (InvalidParamsError e) {
            throw e;
        } catch (JsonParseException | ClassCastException | IllegalStateException | IllegalArgumentException
                | NullPointerException | UnsupportedOperationException e) {
            log.debug("Invalid SendMessage params", e);
            throw new InvalidParamsError();
        }
    }

    private static MessageSendConfiguration parseConfiguration(JsonObject params) {
        JsonElement inlinePushConfig = params.get("pushNotificationConfig");
        JsonElement configuration = params.get("configuration");
        if (inlinePushConfig == null && (configuration == null || configuration.isJsonNull())) {
            return null;
        }
        try {
            MessageSendConfiguration.Builder builder = MessageSendConfiguration.builder();
            if (configuration != null && !configuration.isJsonNull()) {
                if (!configuration.isJsonObject()) {
                    throw invalid("params.configuration must be an object");
                }
                JsonObject configurationObject = configuration.getAsJsonObject();
                optionalInteger(configurationObject, "historyLength", "params.configuration.historyLength")
                        .ifPresent(builder::historyLength);
                optionalBoolean(configurationObject, "returnImmediately", "params.configuration.returnImmediately")
                        .ifPresent(builder::returnImmediately);
                JsonElement nestedPushConfig = configurationObject.get("taskPushNotificationConfig");
                if (nestedPushConfig != null && !nestedPushConfig.isJsonNull()) {
                    builder.taskPushNotificationConfig(parseTaskPushNotificationConfig(nestedPushConfig,
                            "params.configuration.taskPushNotificationConfig"));
                }
            }
            if (inlinePushConfig != null && !inlinePushConfig.isJsonNull()) {
                builder.taskPushNotificationConfig(parseTaskPushNotificationConfig(inlinePushConfig,
                        "params.pushNotificationConfig"));
                builder.returnImmediately(true);
            }
            return builder.build();
        } catch (InvalidParamsError e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("Invalid SendMessage configuration", e);
            throw new InvalidParamsError();
        }
    }

    private static TaskPushNotificationConfig parseTaskPushNotificationConfig(JsonElement value, String path) {
        if (!value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        JsonObject config = value.getAsJsonObject();
        String id = optionalNonBlankString(config, "id", path + ".id").orElse(null);
        String taskId = optionalNonBlankString(config, "taskId", path + ".taskId").orElse(null);
        String url = optionalNonBlankString(config, "callbackUrl", path + ".callbackUrl")
                .or(() -> optionalNonBlankString(config, "url", path + ".url")).orElse(null);
        String token = optionalNonBlankString(config, "token", path + ".token").orElse(null);
        AuthenticationInfo authentication = parseAuthentication(config.get("authentication"), path + ".authentication");
        String tenant = optionalNonBlankString(config, "tenant", path + ".tenant").orElse(null);
        return TaskPushNotificationConfig.builder().id(id).taskId(taskId).url(url).token(token)
                .authentication(authentication).tenant(tenant).build();
    }

    private static AuthenticationInfo parseAuthentication(JsonElement value, String path) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        JsonObject auth = value.getAsJsonObject();
        String scheme = optionalNonBlankString(auth, "scheme", path + ".scheme").orElse(null);
        String credentials = optionalNonBlankString(auth, "credentials", path + ".credentials").orElse(null);
        return new AuthenticationInfo(scheme, credentials);
    }

    static TaskQueryParams parseTaskQueryParams(JsonObject request) {
        try {
            JsonObject params = requiredObject(request, "params", "params");
            requiredNonBlankString(params, "id", "params.id");
            return JsonUtil.fromJson(params.toString(), TaskQueryParams.class);
        } catch (InvalidParamsError e) {
            throw e;
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.debug("Invalid GetTask params", e);
            throw new InvalidParamsError();
        }
    }

    static TaskIdParams parseTaskIdParams(JsonObject request) {
        try {
            JsonObject params = requiredObject(request, "params", "params");
            requiredNonBlankString(params, "id", "params.id");
            return JsonUtil.fromJson(params.toString(), TaskIdParams.class);
        } catch (InvalidParamsError e) {
            throw e;
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.debug("Invalid SubscribeToTask params", e);
            throw new InvalidParamsError();
        }
    }

    private static List<Part<?>> parseParts(JsonArray partObjects) {
        List<Part<?>> parts = new ArrayList<>();
        for (JsonElement element : partObjects) {
            if (!element.isJsonObject()) {
                throw invalid("params.message.parts entries must be objects");
            }
            JsonObject part = element.getAsJsonObject();
            if (part.has("text") && !part.get("text").isJsonNull()) {
                if (!part.get("text").isJsonPrimitive() || !part.getAsJsonPrimitive("text").isString()) {
                    throw invalid("params.message.parts[].text must be a string");
                }
                String text = part.get("text").getAsString();
                if (!text.isBlank()) {
                    Map<String, Object> metadata = parseMetadata(part, "params.message.parts[].metadata");
                    parts.add(metadata.isEmpty() ? new TextPart(text) : new TextPart(text, metadata));
                }
            }
        }
        return parts;
    }

    private static Message buildMessage(JsonObject messageObject, List<Part<?>> parts) {
        String roleValue = optionalNonBlankString(messageObject, "role", "params.message.role").orElse("ROLE_USER");
        var builder = Message.builder().role(Message.Role.valueOf(roleValue)).parts(parts);
        optionalNonBlankString(messageObject, "contextId", "params.message.contextId").ifPresent(builder::contextId);
        optionalNonBlankString(messageObject, "taskId", "params.message.taskId").ifPresent(builder::taskId);
        optionalNonBlankString(messageObject, "messageId", "params.message.messageId").ifPresent(builder::messageId);
        builder.metadata(parseMetadata(messageObject, "params.message.metadata"));
        return builder.build();
    }

    private static Map<String, Object> parseMetadata(JsonObject parent, String path) {
        JsonElement value = parent.get("metadata");
        if (value == null || value.isJsonNull()) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        return GSON.fromJson(value, METADATA_TYPE);
    }

    private static JsonObject requiredObject(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw invalid(path + " is required and must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredNonEmptyArray(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull() || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw invalid(path + " is required and must be a non-empty array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredNonBlankString(JsonObject parent, String memberName, String path) {
        return optionalNonBlankString(parent, memberName, path)
                .orElseThrow(() -> invalid(path + " is required and must be a non-blank string"));
    }

    private static Optional<Integer> optionalInteger(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(path + " must be a number");
        }
        return Optional.of(value.getAsInt());
    }

    private static Optional<Boolean> optionalBoolean(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(path + " must be a boolean");
        }
        return Optional.of(value.getAsBoolean());
    }

    private static Optional<String> optionalNonBlankString(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(path + " must be a string");
        }
        String text = value.getAsString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static InvalidParamsError invalid(String detail) {
        return new InvalidParamsError("Invalid params: " + detail);
    }
}
