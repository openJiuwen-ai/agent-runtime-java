/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;
import java.util.*;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A Message → ServeRequest inbound adapter. Outbound (QueryChunk → A2A Part) is handled by {@link ChunkMapper} +
 * AgentEmitter.
 *
 * @since 0.1.0
 */
public class A2AProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(A2AProtocolAdapter.class);

    private static final Map<String, String> ROLE_MAP = Map.of("ROLE_USER", "user", "ROLE_AGENT", "assistant",
            "ROLE_SYSTEM", "system");

    public ServeRequest toServeRequest(A2AMessageContext ctx) {
        ServeRequest req = new ServeRequest();
        req.setConversationId(ctx.getContextId());
        req.setStream(false); // default non-streaming; overridden by executor for SendStreamingMessage

        // Preserve A2A protocol metadata: MessageSendParams.metadata()
        req.setMetadata(ctx.getMetadata() != null ? ctx.getMetadata() : Map.of());

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
        String rawText = extractText(msg.parts());

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", normalizeRole(msg.role().name()));
        userMsg.put("content", rawText);
        req.setMessages(List.of(userMsg));

        log.info("A2A toServeRequest taskId={} contextId={} conversationId={} textLen={}", ctx.getTaskId(),
                ctx.getContextId(), req.getConversationId(), rawText != null ? rawText.length() : 0);

        return req;
    }

    private String extractText(List<Part<?>> parts) {
        return parts.stream().filter(p -> p instanceof TextPart).map(p -> ((TextPart) p).text())
                .collect(Collectors.joining());
    }

    static String normalizeRole(String raw) {
        return ROLE_MAP.getOrDefault(raw, raw.toLowerCase());
    }
}
