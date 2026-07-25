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
    void firstCallDelegatesToAgentCWithSyncMode() {
        TestRail rail = new TestRail();
        ToolCall call = ToolCall.builder().name("delegate_to_agentc").arguments("{\"message\":\"推荐一道适合团队午餐的菜\"}")
                .build();

        Object result = rail.resolve(call, null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interruptResult -> {
            assertThat(rail.getTools()).extracting("name").contains("delegate_to_agentc");
            assertThat(interruptResult.getRequest().getMessage()).isEqualTo("推荐一道适合团队午餐的菜");
            assertThat(interruptResult.getRequest().getContext()).containsEntry("agentName", "agentc")
                    .containsEntry("_interrupt_kind", "a2a_delegate").doesNotContainKey("_stream_mode");
        });
    }

    @Test
    void resumeReturnsRemoteResultToAgentBToolCall() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(ToolCall.builder().build(), "Agent C 推荐宫保鸡丁");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(rejectResult.getToolResult()).isEqualTo("Agent C 推荐宫保鸡丁"));
    }

    private static final class TestRail extends BToCDelegateRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
