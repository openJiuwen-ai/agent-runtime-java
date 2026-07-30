/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Unit tests for {@link ChunkMapper}.
 */
class ChunkMapperTest {
    private final ChunkMapper mapper = new ChunkMapper();

    @Test
    void answerEnvelopeIsMappedToPlainBusinessText() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK,
                envelope("answer", Map.of("output", "claim=WF-001; decision=approved")));

        assertThat(text(chunk)).isEqualTo("claim=WF-001; decision=approved");
    }

    @Test
    void workflowFinalEnvelopeIsMappedToPlainBusinessText() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK,
                envelope("workflow_final", Map.of("response", "Agent D expense review completed")));

        assertThat(text(chunk)).isEqualTo("Agent D expense review completed");
    }

    @Test
    void workflowFinalEnvelopeWithStructuredOutputIsMappedToBusinessData() {
        Map<String, Object> output = Map.of("auto_result", "Expense claim approved");
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, envelope("workflow_final", Map.of("output", output)));

        assertThat(data(chunk)).isEqualTo(output);
        assertThat(mapper.isTerminalResult(chunk)).isTrue();
    }

    @Test
    void intermediateEnvelopeIsMappedToStructuredData() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, envelope("llm_output", Map.of("content", "working")));

        assertThat(data(chunk)).isEqualTo(chunk.getData());
        assertThat(mapper.isTerminalResult(chunk)).isFalse();
    }

    @Test
    void intermediateJsonStringIsMappedToStructuredData() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK,
                "{\"type\":\"llm_output\",\"payload\":{\"content\":\"working\"}}");

        assertThat(data(chunk)).isEqualTo(Map.of("type", "llm_output", "payload", Map.of("content", "working")));
    }

    @Test
    void remoteOutputKeepsSourceMetadataAndPlainText() {
        Map<String, Object> projection = Map.of("kind", "remote_agent_output", "batchId", "batch-a",
                "toolCallId", "call-a", "target", "agent-a");
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                Map.of("content", "working", "projection", projection));

        TextPart part = part(chunk);

        assertThat(part.text()).isEqualTo("working");
        assertThat(part.metadata()).containsEntry("_remote_invocation", projection);
    }

    @Test
    void structuredRemoteOutputKeepsSourceMetadata() {
        Map<String, Object> projection = Map.of("kind", "remote_agent_output", "batchId", "batch-a",
                "toolCallId", "call-a", "target", "agent-a");
        Map<String, Object> content = envelope("llm_output", Map.of("content", "working"));
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                Map.of("content", content, "projection", projection));

        DataPart part = dataPart(chunk);

        assertThat(part.data()).isEqualTo(content);
        assertThat(part.metadata()).containsEntry("_remote_invocation", projection);
        assertThat(mapper.isTerminalResult(chunk)).isFalse();
    }

    private String text(QueryChunk chunk) {
        return part(chunk).text();
    }

    private TextPart part(QueryChunk chunk) {
        Part<?> value = partValue(chunk);
        if (value instanceof TextPart textPart) {
            return textPart;
        }
        throw new AssertionError("Expected TextPart but got " + value.getClass().getSimpleName());
    }

    private Object data(QueryChunk chunk) {
        return dataPart(chunk).data();
    }

    private DataPart dataPart(QueryChunk chunk) {
        Part<?> value = partValue(chunk);
        if (value instanceof DataPart dataPart) {
            return dataPart;
        }
        throw new AssertionError("Expected DataPart but got " + value.getClass().getSimpleName());
    }

    private Part<?> partValue(QueryChunk chunk) {
        return mapper.toParts(chunk).get(0);
    }

    private static Map<String, Object> envelope(String type, Map<String, Object> payload) {
        return Map.of("type", type, "index", 0, "payload", payload);
    }
}
