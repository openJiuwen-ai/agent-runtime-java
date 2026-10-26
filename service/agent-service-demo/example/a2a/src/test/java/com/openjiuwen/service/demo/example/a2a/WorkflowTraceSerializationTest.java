/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.tracer.TraceWorkflowSpan;
import com.openjiuwen.service.app.controller.a2a.ChunkMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

class WorkflowTraceSerializationTest {
    @Test
    void coreTraceUsesIsoDatesAndOmitsPrivateLlmData() {
        TraceWorkflowSpan span = new TraceWorkflowSpan("trace", "invoke", null, null);
        span.setStartTime(LocalDateTime.parse("2026-10-26T11:47:05"));
        span.setEndTime(LocalDateTime.parse("2026-10-26T11:47:06"));
        span.setLlmInvokeData(Map.of("internal", Map.of("prompt", "private-prompt")));
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("type", "tracer_workflow", "payload", span));

        Part<?> part = new ChunkMapper().toParts(chunk).get(0);
        String json = JsonUtil.OBJECT_MAPPER.toJson(part, Part.class);
        Part<?> restored = JsonUtil.OBJECT_MAPPER.fromJson(json, Part.class);

        assertThat(restored).isInstanceOf(DataPart.class);
        assertThat(json).contains("2026-10-26T11:47:05", "2026-10-26T11:47:06", "tracer_workflow")
                .doesNotContain("llmInvokeData", "private-prompt");
        assertThat(((DataPart) restored).data()).isEqualTo(((DataPart) part).data());
    }
}
