/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for the answer discrimination and business-text extraction in
 * {@link RemoteAgentAnswerExtractor}. The remote caller keeps the AgentCore stream
 * envelope in the forwarded stream (uniform format) and only unwraps the
 * {@code type == "answer"} chunk into the tool result fed back to our LLM.
 */
class RemoteAgentAnswerExtractorTest {
    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("answer envelope is unwrapped to its payload business text")
    void answerEnvelopeUnwrapped() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output", "2");
        payload.put("result_type", "answer");
        String raw = GSON.toJson(envelope("answer", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).contains("2");
    }

    @Test
    @DisplayName("non-answer chunk is left enveloped (forwarded to the caller's stream verbatim)")
    void intermediateChunkNotAnswer() {
        // This llm_output delta even carries text "2", but it must NOT be treated as
        // the answer.
        Map<String, Object> payload = Map.of("content", "2");
        String raw = GSON.toJson(envelope("llm_output", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).isEmpty();
    }

    @Test
    @DisplayName("answer envelope without a text field falls back to the raw envelope text")
    void answerWithoutTextFallsBackToRaw() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", "abc");
        String raw = GSON.toJson(envelope("answer", payload));

        assertThat(RemoteAgentAnswerExtractor.extractAnswer(raw)).contains(raw);
    }

    @Test
    @DisplayName("plain (non-JSON) text is not an answer envelope")
    void plainTextNotAnswer() {
        assertThat(RemoteAgentAnswerExtractor.extractAnswer("hello")).isEmpty();
    }

    @Test
    @DisplayName("extractBusinessText prefers payload text keys, then top level")
    void extractBusinessTextVariants() {
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText("2")).contains("2");
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText("   ")).isEmpty();
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(null)).isEmpty();
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(42)).isEmpty();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", "hel");
        assertThat(RemoteAgentAnswerExtractor.extractBusinessText(envelope("chunk", payload))).contains("hel");
    }

    private static Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("index", 0);
        envelope.put("payload", payload);
        return envelope;
    }
}
