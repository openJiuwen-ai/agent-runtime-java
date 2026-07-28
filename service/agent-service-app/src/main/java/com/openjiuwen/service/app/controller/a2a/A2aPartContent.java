/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts business content from A2A parts for Agent-to-Agent tool results.
 *
 * @since 0.1.0
 */
public final class A2aPartContent {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String REMOTE_INVOCATION_METADATA = "_remote_invocation";

    private A2aPartContent() {
    }

    /**
     * Concatenates business parts, serializing structured data as JSON for the string-based tool result contract.
     *
     * @param parts A2A artifact or message parts
     * @return business content, or an empty string when no business part exists
     */
    public static String extract(List<Part<?>> parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (Part<?> part : parts) {
            if (isInternalProjection(part)) {
                continue;
            }
            if (part instanceof TextPart textPart) {
                content.append(textPart.text());
            } else if (part instanceof DataPart dataPart && dataPart.data() != null) {
                Object data = dataPart.data();
                Optional<Object> terminalValue = AgentCoreEnvelopeText.terminalValue(data);
                if (terminalValue.isEmpty() && AgentCoreEnvelopeText.isStreamEnvelope(data)) {
                    continue;
                }
                Object value = terminalValue.orElse(data);
                content.append(value instanceof String text ? text : GSON.toJson(value));
            }
        }
        return content.toString();
    }

    private static boolean isInternalProjection(Part<?> part) {
        Map<String, Object> metadata = null;
        if (part instanceof TextPart textPart) {
            metadata = textPart.metadata();
        } else if (part instanceof DataPart dataPart) {
            metadata = dataPart.metadata();
        }
        return metadata != null && metadata.containsKey(REMOTE_INVOCATION_METADATA);
    }
}
