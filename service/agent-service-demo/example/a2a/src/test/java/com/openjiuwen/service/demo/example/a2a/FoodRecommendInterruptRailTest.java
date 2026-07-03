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
 * Unit tests for {@link FoodRecommendInterruptRail}.
 */
class FoodRecommendInterruptRailTest {
    @Test
    void firstCallRequiresUserConfirmationFromAgentC() {
        TestRail rail = new TestRail();
        ToolCall call = ToolCall.builder().name("food_recommend").arguments("{\"request\":\"推荐一道适合团队午餐的菜\"}").build();

        Object result = rail.resolve(call, null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interruptResult -> {
            assertThat(rail.getTools()).extracting("name").contains("food_recommend");
            assertThat(interruptResult.getRequest().getMessage()).contains("Agent C").contains("确认")
                    .contains("推荐一道适合团队午餐的菜");
            assertThat(interruptResult.getRequest().getContext()).containsEntry("_interrupt_kind", "ask_user");
        });
    }

    @Test
    void resumeReturnsFoodRecommendationToAgentCModel() {
        TestRail rail = new TestRail();
        ToolCall call = ToolCall.builder().name("food_recommend").arguments("{\"request\":\"推荐适合三人晚餐的菜\"}").build();

        Object result = rail.resolve(call, "同意");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(String.valueOf(rejectResult.getToolResult())).contains("Agent C")
                        .contains("同意").contains("宫保鸡丁").contains("推荐适合三人晚餐的菜"));
    }

    private static final class TestRail extends FoodRecommendInterruptRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
