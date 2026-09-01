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
 * Unit tests for {@link A2aDelegateRail} remote routing.
 */
class A2aDelegateRailTest {
    @Test
    void toolDelegatesToConfiguredAgentBRoute() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(call("delegate_to_agentb"), null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interrupt -> {
            assertThat(interrupt.getRequest().getMessage()).isEqualTo("review expense WF-SSE-001");
            assertThat(interrupt.getRequest().getContext()).containsEntry("agentName", "agentb")
                    .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode");
        });
    }

    @Test
    void resumeReturnsAgentBResult() {
        Object result = new TestRail().resolve(ToolCall.builder().build(), "Agent B result");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                reject -> assertThat(reject.getToolResult()).isEqualTo("Agent B result"));
    }

    private static ToolCall call(String name) {
        return ToolCall.builder().name(name).arguments("{\"message\":\"review expense WF-SSE-001\"}").build();
    }

    private static final class TestRail extends A2aDelegateRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
