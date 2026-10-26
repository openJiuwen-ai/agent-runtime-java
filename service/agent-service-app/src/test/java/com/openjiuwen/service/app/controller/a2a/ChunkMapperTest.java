/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ChunkMapper}.
 */
class ChunkMapperTest {
    private final ChunkMapper mapper = new ChunkMapper();

    @Test
    void preservesGsonPayloadFieldsAndBinaryRepresentation() {
        LegacyPayload payload = new LegacyPayload();
        Part<?> nested = partValue(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("payload", payload)));
        String json = JsonUtil.OBJECT_MAPPER.toJson(nested, Part.class);
        assertThat(json).contains("\"wire_name\":\"legacy\"", "\"bytes\":[1,2]", "9007199254740993")
                .doesNotContain("computed", "javaName");
        assertThat(partValue(new QueryChunk(QueryChunk.TYPE_CHUNK, payload))).isInstanceOfSatisfying(TextPart.class,
                text -> assertThat(text.text()).isEqualTo(new com.google.gson.Gson().toJson(payload)));
    }

    private static class LegacyPayload {
        @com.google.gson.annotations.SerializedName("wire_name")
        private final String javaName = "legacy";
        private final byte[] bytes = {1, 2};
        private final long id = 9007199254740993L;

        public String getComputed() {
            return "derived";
        }
    }

    @Test
    void preservesNullElementsInNestedJavaPayloads() {
        NullablePayload payload = new NullablePayload();
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("payload", payload));

        String json = JsonUtil.OBJECT_MAPPER.toJson(partValue(chunk), Part.class);

        assertThat(json).contains("\"values\":[null,\"2026-10-26T11:47:05\"]");
    }

    private static class NullablePayload {
        private final List<Object> values = Arrays.asList(null, LocalDateTime.parse("2026-10-26T11:47:05"));
    }

    @Test
    void nestedTraceTimesCanBeSerializedByTheA2aSdk() {
        TracePayload trace = new TracePayload(LocalDateTime.parse("2026-10-26T11:47:05"), "success");
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK,
                Map.of("type", "tracer_workflow", "payload", Map.of("spans", List.of(trace))));

        Part<?> part = partValue(chunk);
        String json = JsonUtil.OBJECT_MAPPER.toJson(part, Part.class);

        assertThat(json).contains("2026-10-26T11:47:05", "success", "tracer_workflow");
        assertThat(data(chunk)).isEqualTo(Map.of("type", "tracer_workflow", "payload",
                Map.of("spans", List.of(Map.of("startTime", "2026-10-26T11:47:05", "status", "success")))));
        assertThat(trace.startTime()).isEqualTo(LocalDateTime.parse("2026-10-26T11:47:05"));
    }

    @Test
    void terminalResultsNormalizeDatesAndPreserveBusinessValues() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, envelope("workflow_final", Map.of("output",
                Map.of("approvedAt", LocalDateTime.parse("2026-10-26T11:47:05"), "amount", 3000, "approved", true))));

        assertThat(data(chunk))
                .isEqualTo(Map.of("approvedAt", "2026-10-26T11:47:05", "amount", 3000, "approved", true));
        assertThat(mapper.isTerminalResult(chunk)).isTrue();
    }

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

    private record TracePayload(LocalDateTime startTime, String status) {
    }
}
