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
 * Unit tests for {@link CalcInterruptRail}.
 */
class CalcInterruptRailTest {
    @Test
    void firstCallAsksUserToConfirmTheRequestedExpression() {
        TestRail rail = new TestRail();

        Object result = rail.resolve(toolCall("1+1"), null);

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class, interruptResult -> {
            assertThat(rail.getTools()).extracting("name").contains("calc");
            assertThat(interruptResult.getRequest().getMessage()).contains("Agent B").contains("1+1")
                    .contains("Reply yes or no");
            assertThat(interruptResult.getRequest().getContext()).containsEntry("_interrupt_kind", "ask_user");
        });
    }

    @Test
    void affirmativeResumeCalculatesOriginalExpression() {
        Object result = new TestRail().resolve(toolCall("1 + 1"), "ok");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(String.valueOf(rejectResult.getToolResult()))
                        .isEqualTo("Calculation completed: 1+1 = 2"));
    }

    @Test
    void negativeResumeCancelsCalculation() {
        Object result = new TestRail().resolve(toolCall("15*7"), "no");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(String.valueOf(rejectResult.getToolResult()))
                        .isEqualTo("Calculation cancelled: 15*7"));
    }

    @Test
    void ambiguousResumeAsksForConfirmationAgain() {
        Object result = new TestRail().resolve(toolCall("1+1"), "maybe");

        assertThat(result).isInstanceOfSatisfying(InterruptResult.class,
                interruptResult -> assertThat(interruptResult.getRequest().getMessage()).contains("1+1")
                        .contains("Reply yes or no"));
    }

    @Test
    void divisionByZeroReturnsDeterministicFailure() {
        Object result = new TestRail().resolve(toolCall("1/0"), "yes");

        assertThat(result).isInstanceOfSatisfying(RejectResult.class,
                rejectResult -> assertThat(String.valueOf(rejectResult.getToolResult()))
                        .isEqualTo("Calculation failed: division by zero."));
    }

    private static ToolCall toolCall(String expression) {
        return ToolCall.builder().name("calc").arguments("{\"expression\":\"" + expression + "\"}").build();
    }

    private static final class TestRail extends CalcInterruptRail {
        private Object resolve(ToolCall call, Object resumeInput) {
            return resolveInterrupt(null, call, resumeInput);
        }
    }
}
