/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.part;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Protocol-level hygiene rules for normalized A2A parts (design doc FEAT-036 §4.1).
 *
 * <p>Works on the normalized part map representation (kind + mutually exclusive
 * payload fields, see design §5.2) and never depends on the A2A SDK, so both the
 * runtime inbound parser and the custom-rest SPI bridge share the same limits.
 * Business-level file type validation (EDP-FILE-003) stays out of scope.</p>
 *
 * @since 0.1.0
 */
public final class A2aPartRules {
    private static final int MAX_FILENAME_CHARS = 255;

    private static final int MAX_METADATA_BYTES = 16 * 1024;

    private static final Set<String> KINDS = Set.of("text", "raw", "url", "data");

    /** Discriminator payload fields, one per kind (design §5.2). */
    private static final Map<String, String> KIND_PAYLOAD = Map.of("text", "text", "raw", "bytesBase64", "url", "url",
            "data", "data");

    private A2aPartRules() {
    }

    /**
     * Validates a normalized part list: structural mutual exclusion, base64
     * validity, decoded raw size ≤ {@code maxRawBytes}, per text/data part size
     * ≤ {@code maxTextDataBytes}, and part count ≤ {@code maxParts}.
     *
     * @param parts the normalized part maps (kind + payload fields)
     * @param maxRawBytes maximum decoded raw bytes per raw part
     * @param maxTextDataBytes maximum serialized size per text/data part
     * @param maxParts maximum number of parts
     * @return the first violation description, or empty when all rules pass
     */
    public static Optional<String> validate(List<Map<String, Object>> parts, long maxRawBytes, long maxTextDataBytes,
            int maxParts) {
        if (parts == null || parts.isEmpty()) {
            return Optional.empty();
        }
        if (parts.size() > maxParts) {
            return Optional.of("params.message.parts count exceeds max-parts " + maxParts);
        }
        for (int i = 0; i < parts.size(); i++) {
            Map<String, Object> part = parts.get(i);
            Optional<String> structural = validateOne(part);
            if (structural.isPresent()) {
                return Optional.of("params.message.parts[" + i + "] " + structural.get());
            }
            String kind = String.valueOf(part.get("kind"));
            Optional<String> sizeViolation = validateSize(part, kind, maxRawBytes, maxTextDataBytes);
            if (sizeViolation.isPresent()) {
                return Optional.of("params.message.parts[" + i + "] " + sizeViolation.get());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateOne(Map<String, Object> part) {
        Object rawKind = part.get("kind");
        if (!(rawKind instanceof String kind) || kind.isBlank() || !KINDS.contains(kind)) {
            return Optional.of("params.message.parts kind must be one of text/raw/url/data");
        }
        String expectedField = KIND_PAYLOAD.get(kind);
        boolean payloadPresent = false;
        boolean foreignPresent = false;
        for (Map.Entry<String, String> entry : KIND_PAYLOAD.entrySet()) {
            boolean present = part.containsKey(entry.getValue()) && part.get(entry.getValue()) != null;
            if (entry.getValue().equals(expectedField)) {
                payloadPresent = present;
            } else if (present) {
                foreignPresent = true;
            }
        }
        if (!payloadPresent || foreignPresent) {
            return Optional.of("params.message.parts must contain exactly one of text/raw/url/data");
        }
        if ("url".equals(kind)) {
            String url = String.valueOf(part.get("url"));
            if (url.isBlank()) {
                return Optional.of("params.message.parts url must be a non-blank string");
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return Optional.of("params.message.parts url must use http or https scheme");
            }
        }
        Object filename = part.get("filename");
        if (filename instanceof String name && name.length() > MAX_FILENAME_CHARS) {
            return Optional.of("params.message.parts filename exceeds size limit");
        }
        Object metadata = part.get("metadata");
        if (metadata != null && jsonSize(metadata) > MAX_METADATA_BYTES) {
            return Optional.of("params.message.parts metadata exceeds size limit");
        }
        return Optional.empty();
    }

    private static Optional<String> validateSize(Map<String, Object> part, String kind, long maxRawBytes,
            long maxTextDataBytes) {
        if ("raw".equals(kind)) {
            Object rawBase64 = part.get("bytesBase64");
            if (!(rawBase64 instanceof String base64)) {
                return Optional.of("raw is not valid base64");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                return Optional.of("raw is not valid base64");
            }
            if (decoded.length > maxRawBytes) {
                return Optional.of("raw exceeds max-raw-bytes " + maxRawBytes);
            }
            return Optional.empty();
        }
        if ("text".equals(kind)) {
            long bytes = utf8Length(String.valueOf(part.get("text")));
            return bytes > maxTextDataBytes ? Optional.of("exceeds max-text-data-bytes " + maxTextDataBytes)
                    : Optional.empty();
        }
        if ("data".equals(kind)) {
            long size = jsonSize(part.get("data"));
            return size > maxTextDataBytes ? Optional.of("exceeds max-text-data-bytes " + maxTextDataBytes)
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Estimates the serialized JSON size of a normalized payload value without
     * pulling a JSON library into the spec contract package (no-Spring, minimal
     * dependencies). The estimate is conservative: exact for plain strings and
     * close enough for the hygiene size limits.
     *
     * @param value the payload value (string, number, boolean, map, list, null)
     * @return the estimated serialized size in bytes
     */
    private static long jsonSize(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Boolean bool) {
            return bool ? 4 : 5;
        }
        if (value instanceof String string) {
            return utf8Length(string) + 2;
        }
        if (value instanceof Number number) {
            return String.valueOf(number).length();
        }
        if (value instanceof Map<?, ?> map) {
            long total = 2;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += jsonSize(entry.getKey()) + 1 + 1 + jsonSize(entry.getValue()) + 1;
            }
            return total;
        }
        if (value instanceof List<?> list) {
            long total = 2L + list.size();
            for (Object item : list) {
                total += jsonSize(item);
            }
            return total;
        }
        return String.valueOf(value).length();
    }
}
