/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts business content from A2A parts for Agent-to-Agent tool results.
 *
 * @since 0.1.0
 */
public final class A2aPartContent {
    /** Marks an artifact whose AgentCore terminal envelope has already been removed. */
    public static final String TERMINAL_RESULT_METADATA = "_agentcore_terminal";

    /** Carries structured metadata from a successfully completed AgentCore response. */
    public static final String RESPONSE_METADATA = "_agentcore_response_metadata";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

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
            if (part instanceof TextPart textPart) {
                content.append(textPart.text());
                continue;
            }
            if (!(part instanceof DataPart dataPart) || dataPart.data() == null) {
                continue;
            }
            Object data = dataPart.data();
            Optional<Object> terminalValue = AgentCoreEnvelopeText.terminalValue(data);
            if (terminalValue.isEmpty() && AgentCoreEnvelopeText.isStreamEnvelope(data)) {
                continue;
            }
            Object value = terminalValue.orElse(data);
            content.append(value instanceof String text ? text : GSON.toJson(value));
        }
        return content.toString();
    }

    /**
     * Selects the terminal business result from a completed A2A task.
     *
     * <p>Runtime-produced streaming tasks mark artifacts after removing their AgentCore
     * terminal envelope. Raw terminal envelopes remain supported for compatibility with
     * older Runtime versions. Tasks from generic A2A agents without either signal retain
     * the standard behavior of concatenating their business artifacts.
     *
     * @param task completed remote task
     * @return terminal business content, or concatenated business content when no terminal marker exists
     */
    public static String extractTaskResult(Task task) {
        if (task == null || task.artifacts() == null || task.artifacts().isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        String terminal = "";
        boolean hasTerminal = false;
        for (Artifact artifact : task.artifacts()) {
            if (artifact.metadata() != null
                    && artifact.metadata().containsKey(RemoteAgentCaller.AGENT_EVENT_METADATA)) {
                continue;
            }
            String artifactContent = extract(artifact.parts());
            if (artifact.metadata() != null
                    && Boolean.TRUE.equals(artifact.metadata().get(TERMINAL_RESULT_METADATA))) {
                terminal = artifactContent;
                hasTerminal = true;
                continue;
            }
            Optional<String> rawTerminal = AgentCoreEnvelopeText.terminalText(artifactContent);
            if (rawTerminal.isPresent()) {
                terminal = rawTerminal.get();
                hasTerminal = true;
            } else {
                content.append(artifactContent);
            }
        }
        return hasTerminal ? terminal : content.toString();
    }

    /**
     * Extracts response metadata from the task-level metadata of a successfully completed task.
     *
     * <p>Response metadata is deliberately kept outside business text and is
     * only exposed for a completed task.</p>
     *
     * @param task completed A2A task
     * @return copied response metadata, or an empty map for non-completed tasks
     */
    public static Map<String, Object> extractTaskResponseMetadata(Task task) {
        if (task == null || task.status() == null
                || task.status().state() != org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
            return Map.of();
        }
        return extractResponseMetadata(task.metadata());
    }

    /**
     * Extracts the reserved response channel from transport metadata without
     * exposing unrelated A2A transport fields.
     *
     * @param transportMetadata metadata attached to an A2A message or task
     * @return copied response metadata, or an empty map when the channel is absent
     */
    public static Map<String, Object> extractResponseMetadata(Map<String, Object> transportMetadata) {
        if (transportMetadata == null || !(transportMetadata.get(RESPONSE_METADATA) instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        map.forEach((key, value) -> metadata.put(String.valueOf(key), copyValue(value)));
        return metadata;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, item) -> copied.put(String.valueOf(key), copyValue(item)));
            return copied;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(A2aPartContent::copyValue).toList();
        }
        return value;
    }
}
