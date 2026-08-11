/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Framework-neutral structured agent error carried across service adapters.
 *
 * @param code stable symbolic error code
 * @param numericCode optional framework-specific numeric code
 * @param isRetryable whether the originating framework marks the error retryable
 * @param origin framework layer that produced the error
 * @since 0.1.0
 */
public record AgentError(String code, Integer numericCode, boolean isRetryable, String origin) {
    /** Namespaced A2A message metadata key. */
    public static final String METADATA_KEY = "openjiuwen.error";

    private static final String SCHEMA_VERSION = "1";

    /**
     * Converts this descriptor to its transport-safe representation.
     *
     * @return error metadata map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("code", code);
        if (numericCode != null) {
            value.put("numericCode", numericCode);
        }
        value.put("retryable", isRetryable);
        if (origin != null && !origin.isBlank()) {
            value.put("origin", origin);
        }
        return value;
    }

    /**
     * Reads a descriptor from namespaced message metadata.
     *
     * @param metadata message metadata
     * @return parsed descriptor, or empty for legacy/invalid metadata
     */
    public static Optional<AgentError> fromMetadata(Map<?, ?> metadata) {
        return metadata == null ? Optional.empty() : fromValue(metadata.get(METADATA_KEY));
    }

    /**
     * Reads a descriptor map while tolerating omitted optional fields.
     *
     * @param value raw descriptor value
     * @return parsed descriptor, or empty when no stable code is present
     */
    public static Optional<AgentError> fromValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        String code = stringValue(map.get("code"));
        if (code.isBlank()) {
            return Optional.empty();
        }
        Integer numericCode = map.get("numericCode") instanceof Number number ? number.intValue() : null;
        boolean isRetryable = map.get("retryable") instanceof Boolean isRetryableValue && isRetryableValue;
        return Optional.of(new AgentError(code, numericCode, isRetryable, stringValue(map.get("origin"))));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
