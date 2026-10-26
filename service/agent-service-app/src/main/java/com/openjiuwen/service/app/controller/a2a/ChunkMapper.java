/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight QueryChunk → List{@code <Part<?>>} mapper. Terminal AgentCore envelopes are unwrapped to business
 * text or structured data. Structured payloads become JSON-compatible values in {@link DataPart}.
 *
 * @since 0.1.0
 */
public class ChunkMapper {
    private static final ObjectMapper JAVA_TIME = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    // Keep Gson field semantics for existing business objects. Only java.time
    // values use Jackson; fields marked @JsonIgnore are omitted from Core traces.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes field) {
                    JsonIgnore ignored = field.getAnnotation(JsonIgnore.class);
                    return ignored != null && ignored.value();
                }

                @Override
                public boolean shouldSkipClass(Class<?> type) {
                    return false;
                }
            }).registerTypeAdapterFactory(new TypeAdapterFactory() {
                @Override
                public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                    if (!type.getRawType().getPackageName().equals("java.time")) {
                        return null;
                    }
                    TypeAdapter<JsonElement> json = gson.getAdapter(JsonElement.class);
                    return new TypeAdapter<T>() {
                        @Override
                        public void write(JsonWriter out, T value) throws IOException {
                            json.write(out, JsonParser.parseString(JAVA_TIME.writeValueAsString(value)));
                        }

                        @Override
                        public T read(JsonReader in) throws IOException {
                            return JAVA_TIME.readValue(json.read(in).toString(),
                                    JAVA_TIME.constructType(type.getType()));
                        }
                    }.nullSafe();
                }
            }).create();

    /**
     * Converts a {@link QueryChunk} to a list of A2A SDK {@link Part} objects.
     *
     * @param chunk the query chunk to convert
     * @return the list of parts, never null
     */
    public List<Part<?>> toParts(QueryChunk chunk) {
        if (chunk == null || chunk.getData() == null) {
            return List.of();
        }
        Object data = chunk.getData();
        return List.of(toPart(data));
    }

    /**
     * Determines whether a normal AgentCore chunk carries a terminal business result.
     * Remote Artifact events bypass this mapper and therefore cannot become the
     * parent agent's terminal result.
     *
     * @param chunk query chunk to inspect
     * @return true when the chunk is an AgentCore terminal result
     */
    public boolean isTerminalResult(QueryChunk chunk) {
        return chunk != null && QueryChunk.TYPE_CHUNK.equals(chunk.getType())
                && AgentCoreEnvelopeText.terminalValue(chunk.getData()).isPresent();
    }

    private static Part<?> toPart(Object data) {
        Optional<Object> terminalValue = AgentCoreEnvelopeText.terminalValue(data);
        if (terminalValue.isPresent()) {
            return toBusinessPart(terminalValue.get());
        }
        Object structured = data instanceof String text ? parseStructuredJson(text).orElse(null) : data;
        if (structured instanceof Map || structured instanceof List || structured instanceof Number
                || structured instanceof Boolean) {
            return new DataPart(normalize(structured));
        }
        String text = data instanceof String value ? value : GSON.toJson(data);
        return new TextPart(text);
    }

    private static Part<?> toBusinessPart(Object value) {
        if (value instanceof Map || value instanceof List || value instanceof Number || value instanceof Boolean) {
            return new DataPart(normalize(value));
        }
        String text = value instanceof String stringValue ? stringValue : GSON.toJson(value);
        return new TextPart(text);
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> values = new LinkedHashMap<>();
            map.forEach((key, item) -> values.put(key, normalize(item)));
            return values;
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>();
            list.forEach(item -> values.add(normalize(item)));
            return values;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return jsonValue(GSON.toJsonTree(value));
    }

    // Preserve numeric values rather than round-tripping through Object.class,
    // whose Gson default converts numbers to doubles (including large IDs).
    private static Object jsonValue(JsonElement json) {
        if (json.isJsonNull()) {
            return null;
        }
        if (json.isJsonObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            json.getAsJsonObject().entrySet().forEach(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
            return values;
        }
        if (json.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            json.getAsJsonArray().forEach(value -> values.add(jsonValue(value)));
            return values;
        }
        JsonPrimitive value = json.getAsJsonPrimitive();
        return value.isBoolean() ? value.getAsBoolean() : value.isNumber() ? value.getAsNumber() : value.getAsString();
    }

    private static Optional<Object> parseStructuredJson(String text) {
        try {
            Object value = GSON.fromJson(text, Object.class);
            return value instanceof Map || value instanceof List ? Optional.of(value) : Optional.empty();
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }
}
