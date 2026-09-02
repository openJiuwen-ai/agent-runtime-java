/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.service.spec.part.A2aPartLimits;
import com.openjiuwen.service.spec.part.A2aPartRules;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AuthenticationInfo;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Parses and validates method-specific A2A JSON-RPC parameters before they are passed to the SDK.
 */
final class A2aJsonRpcParamsParser {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcParamsParser.class);

    private static final Gson GSON = new Gson();

    /** Parses DataPart payloads with SDK-aligned number semantics (integers as Long). */
    private static final Gson DATA_GSON = new GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create();

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
            Message message = buildMessage(messageObject, parts);
            MessageSendParams.Builder builder = MessageSendParams.builder().message(message)
                    .metadata(parseMetadata(params, "params.metadata"));
            parseConfiguration(params).ifPresent(builder::configuration);
            return builder.build();
        } catch (InvalidParamsError e) {
            throw e;
        } catch (JsonParseException | ClassCastException | IllegalStateException | IllegalArgumentException
                | NullPointerException | UnsupportedOperationException e) {
            log.debug("Invalid SendMessage params", e);
            throw new InvalidParamsError();
        }
    }

    private static Optional<MessageSendConfiguration> parseConfiguration(JsonObject params) {
        JsonElement inlinePushConfig = params.get("pushNotificationConfig");
        JsonElement configuration = params.get("configuration");
        if (inlinePushConfig == null && (configuration == null || configuration.isJsonNull())) {
            return Optional.empty();
        }
        try {
            MessageSendConfiguration.Builder builder = MessageSendConfiguration.builder();
            if (configuration != null && !configuration.isJsonNull()) {
                if (!configuration.isJsonObject()) {
                    throw invalid("params.configuration must be an object");
                }
                JsonObject configurationObject = configuration.getAsJsonObject();
                optionalInteger(configurationObject, "historyLength", "params.configuration.historyLength")
                        .ifPresent(value -> builder.historyLength(value));
                optionalBoolean(configurationObject, "returnImmediately", "params.configuration.returnImmediately")
                        .ifPresent(builder::returnImmediately);
                JsonElement nestedPushConfig = configurationObject.get("taskPushNotificationConfig");
                if (nestedPushConfig != null && !nestedPushConfig.isJsonNull()) {
                    builder.taskPushNotificationConfig(parseTaskPushNotificationConfig(nestedPushConfig,
                            "params.configuration.taskPushNotificationConfig"));
                }
            }
            if (inlinePushConfig != null && !inlinePushConfig.isJsonNull()) {
                builder.taskPushNotificationConfig(
                        parseTaskPushNotificationConfig(inlinePushConfig, "params.pushNotificationConfig"));
                builder.returnImmediately(true);
            }
            return Optional.of(builder.build());
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
        TaskPushNotificationConfig.Builder builder = TaskPushNotificationConfig.builder();
        optionalNonBlankString(config, "id", path + ".id").ifPresent(builder::id);
        optionalNonBlankString(config, "taskId", path + ".taskId").ifPresent(builder::taskId);
        String url = optionalNonBlankString(config, "callbackUrl", path + ".callbackUrl")
                .or(() -> optionalNonBlankString(config, "url", path + ".url"))
                .orElseThrow(() -> invalid(path + ".callbackUrl is required and must be a non-blank string"));
        builder.url(url);
        optionalNonBlankString(config, "token", path + ".token").ifPresent(builder::token);
        parseAuthentication(config.get("authentication"), path + ".authentication").ifPresent(builder::authentication);
        optionalNonBlankString(config, "tenant", path + ".tenant").ifPresent(builder::tenant);
        return builder.build();
    }

    private static Optional<AuthenticationInfo> parseAuthentication(JsonElement value, String path) {
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonObject()) {
            throw invalid(path + " must be an object");
        }
        JsonObject auth = value.getAsJsonObject();
        Optional<String> scheme = optionalNonBlankString(auth, "scheme", path + ".scheme");
        Optional<String> credentials = optionalNonBlankString(auth, "credentials", path + ".credentials");
        return scheme.isPresent() && credentials.isPresent()
                ? Optional.of(new AuthenticationInfo(scheme.get(), credentials.get()))
                : Optional.empty();
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
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int i = 0; i < partObjects.size(); i++) {
            JsonElement element = partObjects.get(i);
            if (!element.isJsonObject()) {
                throw invalid("params.message.parts entries must be objects");
            }
            Map<String, Object> part = parseNormalizedPart(element.getAsJsonObject(),
                    "params.message.parts[" + i + "]");
            if (part != null) {
                normalized.add(part);
            }
        }
        A2aPartRules.validate(normalized, A2aPartLimits.DEFAULT_MAX_RAW_BYTES,
                A2aPartLimits.DEFAULT_MAX_TEXT_DATA_BYTES, A2aPartLimits.DEFAULT_MAX_PARTS).ifPresent(msg -> {
                    throw invalid(msg);
                });
        if (normalized.isEmpty()) {
            throw invalid("params.message.parts must contain at least one part");
        }
        return toSdkParts(normalized);
    }

    /**
     * Parses one wire part into the normalized map representation (design FEAT-036 §5.2):
     * exactly one content field among text/raw/url/data plus shared metadata. Blank text
     * parts keep the legacy skip behavior; a part with no content field is rejected.
     */
    private static Map<String, Object> parseNormalizedPart(JsonObject part, String path) {
        String text = null;
        if (part.has("text") && !part.get("text").isJsonNull()) {
            if (!part.get("text").isJsonPrimitive() || !part.getAsJsonPrimitive("text").isString()) {
                throw invalid(path + ".text must be a string");
            }
            String value = part.get("text").getAsString();
            if (!value.isBlank()) {
                text = value;
            }
        }
        JsonElement raw = contentOrNull(part, "raw");
        JsonElement url = contentOrNull(part, "url");
        JsonElement data = contentOrNull(part, "data");
        List<String> present = new ArrayList<>();
        if (text != null) {
            present.add("text");
        }
        if (raw != null) {
            present.add("raw");
        }
        if (url != null) {
            present.add("url");
        }
        if (data != null) {
            present.add("data");
        }
        if (present.size() > 1) {
            throw invalid(path + " must contain exactly one of text/raw/url/data");
        }
        if (present.isEmpty()) {
            if (part.has("text")) {
                return null; // blank text part, legacy skip
            }
            throw invalid(path + " must contain exactly one of text/raw/url/data");
        }
        String kind = present.get(0);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", kind);
        switch (kind) {
        case "text" -> normalized.put("text", text);
        case "raw" -> normalized.put("bytesBase64", requiredString(raw, path + ".raw"));
        case "url" -> normalized.put("url", requiredString(url, path + ".url"));
        case "data" -> normalized.put("data", DATA_GSON.fromJson(data, Object.class));
        default -> throw invalid(path + " must contain exactly one of text/raw/url/data");
        }
        optionalNonBlankString(part, "filename", path + ".filename").ifPresent(v -> normalized.put("filename", v));
        optionalNonBlankString(part, "mediaType", path + ".mediaType").ifPresent(v -> normalized.put("mediaType", v));
        Map<String, Object> metadata = parseMetadata(part, path + ".metadata");
        if (!metadata.isEmpty()) {
            normalized.put("metadata", metadata);
        }
        return normalized;
    }

    private static JsonElement contentOrNull(JsonObject part, String member) {
        JsonElement value = part.get(member);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static String requiredString(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(path + " must be a string");
        }
        return value.getAsString();
    }

    private static List<Part<?>> toSdkParts(List<Map<String, Object>> normalized) {
        List<Part<?>> parts = new ArrayList<>();
        for (Map<String, Object> part : normalized) {
            String kind = String.valueOf(part.get("kind"));
            Map<String, Object> metadata = part.get("metadata") instanceof Map<?, ?> rawMetadata
                    ? castMetadata(rawMetadata)
                    : null;
            switch (kind) {
            case "text" -> parts.add(metadata == null ? new TextPart((String) part.get("text"))
                    : new TextPart((String) part.get("text"), metadata));
            case "raw" -> parts.add(new FilePart(
                    new FileWithBytes(mediaTypeOf(part), filenameOf(part), (String) part.get("bytesBase64")),
                    metadata));
            case "url" -> parts.add(new FilePart(
                    new FileWithUri(mediaTypeOf(part), filenameOf(part), (String) part.get("url")), metadata));
            case "data" -> parts.add(new DataPart(part.get("data"), metadata));
            default -> throw invalid("params.message.parts kind must be one of text/raw/url/data");
            }
        }
        return parts;
    }

    private static String filenameOf(Map<String, Object> part) {
        return part.get("filename") instanceof String filename ? filename : "";
    }

    private static String mediaTypeOf(Map<String, Object> part) {
        return part.get("mediaType") instanceof String mediaType ? mediaType : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMetadata(Map<?, ?> rawMetadata) {
        return (Map<String, Object>) rawMetadata;
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

    private static OptionalInt optionalInteger(JsonObject parent, String memberName, String path) {
        JsonElement value = parent.get(memberName);
        if (value == null || value.isJsonNull()) {
            return OptionalInt.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(path + " must be a number");
        }
        return OptionalInt.of(value.getAsInt());
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
