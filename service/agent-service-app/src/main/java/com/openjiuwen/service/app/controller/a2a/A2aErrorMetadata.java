/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Encodes framework-neutral failures in the OpenJiuwen A2A metadata extension.
 *
 * @since 0.1.0
 */
public final class A2aErrorMetadata {
    /** Namespaced A2A message metadata key. */
    public static final String KEY = "openjiuwen.error";

    private static final String SCHEMA_VERSION = "1";

    private A2aErrorMetadata() {
    }

    /**
     * Encodes a descriptor as the versioned A2A metadata value.
     *
     * @param descriptor failure descriptor
     * @return metadata value
     */
    public static Map<String, Object> encode(AgentFailureDescriptor descriptor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("code", descriptor.code());
        if (descriptor.numericCode() != null) {
            value.put("numericCode", descriptor.numericCode());
        }
        value.put("retryable", descriptor.retryable());
        return value;
    }

    /**
     * Reads a descriptor from A2A message metadata.
     *
     * @param metadata message metadata
     * @return parsed descriptor, or empty for legacy or invalid metadata
     */
    public static Optional<AgentFailureDescriptor> decode(Map<?, ?> metadata) {
        return metadata == null ? Optional.empty() : decodeValue(metadata.get(KEY));
    }

    /**
     * Reads a descriptor from a previously encoded metadata value.
     *
     * @param value encoded value
     * @return parsed descriptor, or empty when no stable code is present
     */
    public static Optional<AgentFailureDescriptor> decodeValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        String code = map.get("code") == null ? "" : String.valueOf(map.get("code"));
        if (code.isBlank()) {
            return Optional.empty();
        }
        Integer numericCode = map.get("numericCode") instanceof Number number ? number.intValue() : null;
        boolean isRetryable = map.get("retryable") instanceof Boolean retryable && retryable;
        return Optional.of(new AgentFailureDescriptor(code, numericCode, isRetryable));
    }
}
