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
 * Protocol-level hygiene rules for normalized A2A parts.
 *
 * <p>Works on the normalized part map representation (kind + mutually exclusive
 * payload fields) and never depends on the A2A SDK, so both the
 * runtime inbound parser and the custom-rest SPI bridge share the same limits.
 * Business-level file type validation stays out of scope.</p>
 *
 * @since 0.1.0
 */
public final class A2aPartRules {
    private static final int MAX_FILENAME_CHARS = 255;

    private static final int MAX_METADATA_BYTES = 16 * 1024;

    private static final Set<String> KINDS = Set.of("text", "raw", "url", "data");

    /** Discriminator payload fields, one per kind. */
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
        boolean isPayloadPresent = false;
        boolean isForeignPresent = false;
        for (Map.Entry<String, String> entry : KIND_PAYLOAD.entrySet()) {
            boolean isFieldPresent = part.containsKey(entry.getValue()) && part.get(entry.getValue()) != null;
            if (entry.getValue().equals(expectedField)) {
                isPayloadPresent = isFieldPresent;
            } else {
                isForeignPresent |= isFieldPresent;
            }
        }
        if (!isPayloadPresent || isForeignPresent) {
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
     * Computes the exact serialized size of a normalized payload value in
     * compact JSON form (no whitespace, UTF-8 encoding), without pulling a
     * JSON library into the spec contract package. Containers count braces,
     * brackets, one colon per pair, and one comma between adjacent members;
     * string literals count enclosing quotes and per-character escape
     * expansions, so payloads at exactly the limit pass and escaped payloads
     * cannot slip under the limit.
     *
     * @param value the payload value (string, number, boolean, map, list, null)
     * @return the serialized size in bytes
     */
    private static long jsonSize(Object value) {
        if (value == null) {
            return 4L;
        }
        if (value instanceof Boolean isTrue) {
            return isTrue ? 4L : 5L;
        }
        if (value instanceof String stringValue) {
            return jsonStringLength(stringValue);
        }
        if (value instanceof Number numberValue) {
            return String.valueOf(numberValue).length();
        }
        if (value instanceof Map<?, ?> mapValue) {
            long total = 2L;
            boolean isFirst = true;
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (!isFirst) {
                    total += 1L;
                }
                isFirst = false;
                total += jsonSize(entry.getKey()) + 1L + jsonSize(entry.getValue());
            }
            return total;
        }
        if (value instanceof List<?> listValue) {
            long total = 2L;
            boolean isFirst = true;
            for (Object item : listValue) {
                if (!isFirst) {
                    total += 1L;
                }
                isFirst = false;
                total += jsonSize(item);
            }
            return total;
        }
        return String.valueOf(value).length();
    }

    /**
     * Computes the serialized size of a JSON string literal: two enclosing
     * quotes plus per-character escape expansion. Quotes and backslashes
     * double; the short-escaped control characters take 2 bytes; other control
     * characters expand to a 6-byte unicode escape; non-ASCII characters count
     * as their UTF-8 encoding width.
     *
     * @param value the string to measure
     * @return the serialized size in bytes
     */
    private static long jsonStringLength(String value) {
        long total = 2L;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            total += jsonCharWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return total;
    }

    /**
     * Returns the serialized width of a single code point inside a JSON string
     * literal: the short-escaped control characters take 2 bytes, other
     * control characters expand to a 6-byte unicode escape, and remaining
     * characters keep their UTF-8 encoding width.
     *
     * @param codePoint the code point to measure
     * @return the serialized width in bytes
     */
    private static long jsonCharWidth(int codePoint) {
        switch (codePoint) {
            case '"':
            case '\\':
            case '\b':
            case '\f':
            case '\n':
            case '\r':
            case '\t':
                return 2L;
            default:
                if (codePoint < 0x20) {
                    return 6L;
                }
                if (codePoint < 0x80) {
                    return 1L;
                }
                if (codePoint < 0x800) {
                    return 2L;
                }
                return codePoint < 0x10000 ? 3L : 4L;
        }
    }
}
