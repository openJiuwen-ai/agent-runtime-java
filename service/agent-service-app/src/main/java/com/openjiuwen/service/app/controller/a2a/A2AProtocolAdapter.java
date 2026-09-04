/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A2A Message → ServeRequest inbound adapter. Outbound (QueryChunk → A2A Part)
 * is handled by {@link ChunkMapper} +
 * AgentEmitter.
 *
 * @since 0.1.0
 */
public class A2AProtocolAdapter {
    private static final Logger log = LoggerFactory.getLogger(A2AProtocolAdapter.class);

    private static final Map<String, String> ROLE_MAP = Map.of("ROLE_USER", "user", "ROLE_AGENT", "assistant",
            "ROLE_SYSTEM", "system");

    private static final String PARENT_TASK_ID = "runtime.parentTaskId";

    private static final String REMOTE_TOOL_INPUTS = "runtime.remoteToolInputs";

    private static final List<String> RESERVED_METADATA_KEYS = List.of(PARENT_TASK_ID, REMOTE_TOOL_INPUTS,
            "runtime.remoteBatchId", "runtime.remoteToolResults");

    /**
     * Converts an A2A message context into an internal {@link ServeRequest}.
     *
     * @param ctx the A2A message context
     * @return the serve request
     */
    public ServeRequest toServeRequest(A2AMessageContext ctx) {
        ServeRequest req = new ServeRequest();
        req.setConversationId(ctx.getContextId());
        req.setStream(false); // default non-streaming; overridden by executor for SendStreamingMessage

        // Preserve A2A protocol metadata: MessageSendParams.metadata()
        // headers → userId / spaceId / tenantId
        if (ctx.getHeaders() != null) {
            req.setUserId(ctx.getHeaders().getOrDefault("x-user-id", "anonymous"));
            req.setSpaceId(ctx.getHeaders().getOrDefault("x-space-id", "default"));
            if (ctx.getHeaders().containsKey("x-tenant-id")) {
                req.setTenantId(ctx.getHeaders().get("x-tenant-id"));
            }
        }

        // message.parts → text → content (passthrough, no JSON parsing)
        Message msg = ctx.getA2aMessage();
        TextExtraction extraction = extractText(msg.parts());
        String rawText = extraction.rawText();
        req.setMetadata(trustedMetadata(ctx, extraction.targetedInputs()));

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", normalizeRole(msg.role().name()));
        userMsg.put("content", rawText);
        // message.parts → normalized part maps, order preserved
        List<Map<String, Object>> normalizedParts = extractParts(msg.parts());
        if (!normalizedParts.isEmpty()) {
            userMsg.put("parts", normalizedParts);
        }
        if (msg.metadata() != null) {
            userMsg.put("metadata", new LinkedHashMap<>(msg.metadata()));
        }
        req.setMessages(List.of(userMsg));

        log.info("A2A toServeRequest taskId={} contextId={} conversationId={} textLen={}", ctx.getTaskId(),
                ctx.getContextId(), req.getConversationId(), rawText != null ? rawText.length() : 0);

        return req;
    }

    /**
     * Normalizes SDK parts into the unified map representation:
     * kind = text/raw/url/data plus shared metadata fields, preserving inbound order.
     *
     * @param parts the inbound SDK message parts in wire order
     * @return the normalized part maps; unsupported part types are skipped
     */
    private static List<Map<String, Object>> extractParts(List<Part<?>> parts) {
        List<Map<String, Object>> normalized = new ArrayList<>(parts.size());
        for (Part<?> part : parts) {
            Map<String, Object> entry = null;
            if (part instanceof TextPart textPart) {
                entry = new LinkedHashMap<>();
                entry.put("kind", "text");
                entry.put("text", textPart.text());
                copyMetadata(entry, textPart.metadata());
            } else if (part instanceof FilePart filePart && filePart.file() instanceof FileWithBytes withBytes) {
                entry = new LinkedHashMap<>();
                entry.put("kind", "raw");
                entry.put("bytesBase64", withBytes.bytes());
                entry.put("byteSize", decodedByteSize(withBytes.bytes()));
                putIfNotBlank(entry, "filename", withBytes.name());
                putIfNotBlank(entry, "mediaType", withBytes.mimeType());
                copyMetadata(entry, filePart.metadata());
            } else if (part instanceof FilePart filePart && filePart.file() instanceof FileWithUri withUri) {
                entry = new LinkedHashMap<>();
                entry.put("kind", "url");
                entry.put("url", withUri.uri());
                putIfNotBlank(entry, "filename", withUri.name());
                putIfNotBlank(entry, "mediaType", withUri.mimeType());
                copyMetadata(entry, filePart.metadata());
            } else if (part instanceof DataPart dataPart) {
                entry = new LinkedHashMap<>();
                entry.put("kind", "data");
                entry.put("data", dataPart.data());
                copyMetadata(entry, dataPart.metadata());
            } else {
                // unsupported part type: skipped, entry stays null
                log.debug("Skipping unsupported A2A part type {}", part.getClass().getName());
            }
            if (entry != null) {
                normalized.add(entry);
            }
        }
        return normalized;
    }

    private static long decodedByteSize(String bytesBase64) {
        if (bytesBase64 == null || bytesBase64.isEmpty()) {
            return 0L;
        }
        return Base64.getMimeDecoder().decode(bytesBase64).length;
    }

    private static void putIfNotBlank(Map<String, Object> entry, String key, String value) {
        if (value != null && !value.isBlank()) {
            entry.put(key, value);
        }
    }

    private static void copyMetadata(Map<String, Object> entry, Map<String, Object> metadata) {
        if (metadata != null && !metadata.isEmpty()) {
            entry.put("metadata", new LinkedHashMap<>(metadata));
        }
    }

    private static TextExtraction extractText(List<Part<?>> parts) {
        StringBuilder rawText = new StringBuilder();
        Map<String, StringBuilder> grouped = new LinkedHashMap<>();
        boolean hasTargetedPart = false;
        boolean isEveryPartTargeted = true;
        for (Part<?> part : parts) {
            if (!(part instanceof TextPart textPart)) {
                continue;
            }
            rawText.append(textPart.text());
            Object rawToolCallId = textPart.metadata() != null ? textPart.metadata().get("toolCallId") : null;
            if (rawToolCallId instanceof String toolCallId && !toolCallId.isBlank()) {
                hasTargetedPart = true;
                grouped.computeIfAbsent(toolCallId, ignored -> new StringBuilder()).append(textPart.text());
            } else {
                isEveryPartTargeted = false;
            }
        }
        if (hasTargetedPart && !isEveryPartTargeted) {
            throw new IllegalArgumentException("REMOTE_TOOL_INPUT_TARGET_MIXED");
        }
        Map<String, String> targetedInputs = new LinkedHashMap<>();
        if (hasTargetedPart) {
            grouped.forEach((toolCallId, text) -> targetedInputs.put(toolCallId, text.toString()));
        }
        return new TextExtraction(rawText.toString(), targetedInputs);
    }

    private static Map<String, Object> trustedMetadata(A2AMessageContext ctx, Map<String, String> targetedInputs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ctx.getMetadata() != null) {
            metadata.putAll(ctx.getMetadata());
        }
        RESERVED_METADATA_KEYS.forEach(metadata::remove);
        if (ctx.getTaskId() != null && !ctx.getTaskId().isBlank()) {
            metadata.put(PARENT_TASK_ID, ctx.getTaskId());
        }
        if (!targetedInputs.isEmpty()) {
            metadata.put(REMOTE_TOOL_INPUTS, targetedInputs);
        }
        return metadata;
    }

    static String normalizeRole(String raw) {
        return ROLE_MAP.getOrDefault(raw, raw.toLowerCase(Locale.ROOT));
    }

    private record TextExtraction(String rawText, Map<String, String> targetedInputs) {
    }
}
