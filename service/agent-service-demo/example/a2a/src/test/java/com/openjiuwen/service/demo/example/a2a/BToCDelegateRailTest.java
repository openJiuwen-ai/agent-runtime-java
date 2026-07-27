/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.harness.rails.interrupt.InterruptResult;
import com.openjiuwen.harness.rails.interrupt.RejectResult;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BToCDelegateRail}.
 */
class BToCDelegateRailTest {
    @Test
    void streamingToolSelectsStreamingAgentCRoute() {
        TestRail rail = new TestRail();
        ToolCall call = call(BToCDelegateRail.STREAMING_TOOL_NAME);

        Object result = rail.resolve(call, null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interruptResult -> {
            assertThat(rail.getTools()).extracting("name").contains(BToCDelegateRail.STREAMING_TOOL_NAME,
                    BToCDelegateRail.NON_STREAMING_TOOL_NAME);
            assertThat(interruptResult.getRequest().getMessage()).isEqualTo("Recommend a team lunch dish");
            assertThat(interruptResult.getRequest().getContext()).containsEntry("agentName", "agentc-streaming")
                    .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode");
        });
    }

    @Test
    void nonStreamingToolSelectsNonStreamingAgentCRoute() {
        Object result = new TestRail().resolve(call(BToCDelegateRail.NON_STREAMING_TOOL_NAME), null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class,
                interrupt -> assertThat(interrupt.getRequest().getContext())
                        .containsEntry("agentName", "agentc-nonstreaming")
                        .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode"));
    }

    @Test
    void resumeReturnsRemoteResultToAgentBToolCall() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(ToolCall.builder().build(), "Agent C recommends Kung Pao chicken");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(rejectResult.getToolResult())
                        .isEqualTo("Agent C recommends Kung Pao chicken"));
    }

    private static ToolCall call(String name) {
        return ToolCall.builder().name(name).arguments("{\"message\":\"Recommend a team lunch dish\"}").build();
    }

    private static final class TestRail extends BToCDelegateRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
