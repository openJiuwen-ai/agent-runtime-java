/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.QueryResponse;

import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records one business reply through ordered status events before the terminal event.
 * The SDK moves the previous status message into history on the next status update.
 * Internal reasoning and remote agent artifacts remain outside the parent reply.
 *
 * @since 0.1.4
 */
final class A2AReplyHistory {
    private final StringBuilder deltas = new StringBuilder();

    private final List<Part<?>> terminal = new ArrayList<>();

    void accept(List<Part<?>> parts, boolean isTerminal) {
        if (isTerminal) {
            terminal.addAll(parts);
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart text) {
                deltas.append(text.text());
                continue;
            }

            // Reasoning and non-business parts do not belong in the reply history.
            if (part instanceof DataPart data && data.data() instanceof Map<?, ?> envelope
                    && "llm_output".equals(envelope.get("type"))) {
                AgentCoreEnvelopeText.businessText(envelope).ifPresent(deltas::append);
            }
        }
    }

    void finish(AgentEmitter emitter, QueryResponse response) {
        List<Part<?>> parts = List.copyOf(terminal);
        if (parts.isEmpty() && response != null) {
            parts = AgentCoreEnvelopeText.businessText(response.getResult())
                    .map(text -> List.<Part<?>>of(new TextPart(text))).orElseGet(List::of);
        }
        if (parts.isEmpty() && !deltas.isEmpty()) {
            parts = List.of(new TextPart(deltas.toString()));
        }
        if (!parts.isEmpty()) {
            emitter.updateStatus(TaskState.TASK_STATE_WORKING, emitter.newAgentMessage(parts, Map.of()));
        }
        terminal.clear();
        deltas.setLength(0);
    }
}
