/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests message selection and message-scoped metadata access.
 *
 * @since 0.1.0
 */
class ServeRequestTest {
    @Test
    void returnsMetadataFromSameLatestUserMessageAsQuery() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(message("user", "first", Map.of("trace", "old")),
                message("assistant", "middle", Map.of("trace", "agent")),
                message("user", "latest", Map.of("trace", "current"))));

        assertThat(request.lastUserQuery()).isEqualTo("latest");
        assertThat(request.lastUserMessageMetadata()).containsExactlyEntriesOf(Map.of("trace", "current"));
    }

    @Test
    void returnsDefensiveMetadataCopyAndEmptyMapWhenAbsent() {
        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("trace", "original");
        Map<String, Object> sourceMessage = message("user", "hello", sourceMetadata);
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(sourceMessage));

        Map<String, Object> extracted = request.lastUserMessageMetadata();
        extracted.put("trace", "changed");

        assertThat(sourceMetadata).containsEntry("trace", "original");
        sourceMessage.remove("metadata");
        assertThat(request.lastUserMessageMetadata()).isEmpty();
    }

    private static Map<String, Object> message(String role, String content, Map<String, Object> metadata) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        message.put("metadata", metadata);
        return message;
    }
}
