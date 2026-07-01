/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link A2AAgentExecutor#extractBusinessText(Object)} — the
 * business-text extraction that keeps the
 * AgentCore stream envelope out of A2A artifacts (and therefore out of the
 * remote caller's tool result).
 */
class A2AAgentExecutorExtractTest {
    @Test
    @DisplayName("answer envelope yields payload.output, dropping type/index/result_type noise")
    void extractsAnswerOutput() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output", "2");
        payload.put("result_type", "answer");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("index", 0);
        envelope.put("payload", payload);

        assertThat(A2AAgentExecutor.extractBusinessText(envelope)).contains("2");
    }

    @Test
    @DisplayName("intermediate chunk yields payload.delta")
    void extractsChunkDelta() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", "hel");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "chunk");
        envelope.put("index", 1);
        envelope.put("payload", payload);

        assertThat(A2AAgentExecutor.extractBusinessText(envelope)).contains("hel");
    }

    @Test
    @DisplayName("plain string data passes through unchanged")
    void passesThroughString() {
        assertThat(A2AAgentExecutor.extractBusinessText("2")).contains("2");
    }

    @Test
    @DisplayName("payload without a text field returns empty so the caller falls back to raw mapping")
    void emptyWhenNoTextField() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", "abc");
        payload.put("meta", Map.of("k", "v"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "trace");
        envelope.put("payload", payload);

        assertThat(A2AAgentExecutor.extractBusinessText(envelope)).isEmpty();
    }

    @Test
    @DisplayName("blank string is treated as no content")
    void emptyWhenBlank() {
        assertThat(A2AAgentExecutor.extractBusinessText("   ")).isEmpty();
        assertThat(A2AAgentExecutor.extractBusinessText(null)).isEmpty();
        assertThat(A2AAgentExecutor.extractBusinessText(42)).isEmpty();
    }

    @Test
    @DisplayName("answer chunk is unwrapped to business text")
    void answerChunkUnwrapped() {
        Map<String, Object> payload = Map.of("output", "2", "result_type", "answer");
        Map<String, Object> envelope = Map.of("type", "answer", "index", 0, "payload", payload);

        List<Part<?>> parts = A2AAgentExecutor.toArtifactParts(new QueryChunk(QueryChunk.TYPE_ANSWER, envelope),
                new ChunkMapper());

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).isInstanceOfSatisfying(TextPart.class, tp -> assertThat(tp.text()).isEqualTo("2"));
    }

    @Test
    @DisplayName("intermediate (non-answer) chunk keeps the raw envelope — consistent stream format")
    void intermediateChunkKeepsEnvelope() {
        // Even though this llm_output delta carries text "2", it must NOT be unwrapped:
        // only the answer is.
        Map<String, Object> payload = Map.of("content", "2");
        Map<String, Object> envelope = Map.of("type", "llm_output", "index", 1, "payload", payload);

        List<Part<?>> parts = A2AAgentExecutor.toArtifactParts(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope),
                new ChunkMapper());

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).isInstanceOfSatisfying(TextPart.class, tp -> {
            assertThat(tp.text()).contains("\"type\":\"llm_output\"");
            assertThat(tp.text()).contains("\"content\":\"2\"");
        });
    }
}
