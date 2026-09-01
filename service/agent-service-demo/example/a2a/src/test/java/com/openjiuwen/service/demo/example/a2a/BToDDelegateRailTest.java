/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.harness.rails.interrupt.InterruptResult;
import com.openjiuwen.harness.rails.interrupt.RejectResult;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Unit tests for {@link BToDDelegateRail}.
 */
class BToDDelegateRailTest {
    private static final String CLAIM_ARGUMENTS = """
            {"claim_id":"WF-001","category":"hotel","unit_price":1000,
             "quantity":3,"total":3000,"currency":"CNY"}
            """;

    @Test
    void streamingToolDelegatesCanonicalClaimToStreamingAgentDRoute() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(call(BToDDelegateRail.STREAMING_TOOL_NAME), null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interrupt -> {
            assertThat(interrupt.getRequest().getContext()).containsEntry("agentName", "agentd-streaming")
                    .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode");
            assertThat(claim(interrupt.getRequest().getMessage())).containsEntry("claim_id", "WF-001")
                    .containsEntry("category", "hotel").containsEntry("currency", "CNY");
        });
    }

    @Test
    void nonStreamingToolSelectsNonStreamingAgentDRoute() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(call(BToDDelegateRail.NON_STREAMING_TOOL_NAME), null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class,
                interrupt -> assertThat(interrupt.getRequest().getContext())
                        .containsEntry("agentName", "agentd-nonstreaming")
                        .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode"));
    }

    @Test
    void resumeReturnsWorkflowResultToAgentB() {
        Object result = new TestRail().resolve(ToolCall.builder().build(), "Agent D expense review completed");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                reject -> assertThat(reject.getToolResult()).isEqualTo("Agent D expense review completed"));
    }

    private static ToolCall call(String name) {
        return ToolCall.builder().name(name).arguments(CLAIM_ARGUMENTS).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claim(String json) {
        return new Gson().fromJson(json, Map.class);
    }

    private static final class TestRail extends BToDDelegateRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
