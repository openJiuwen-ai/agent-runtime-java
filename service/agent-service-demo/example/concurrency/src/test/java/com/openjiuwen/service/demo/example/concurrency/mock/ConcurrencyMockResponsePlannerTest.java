/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for deterministic mock LLM planning.
 *
 * @since 0.1.0
 */
class ConcurrencyMockResponsePlannerTest {
    @Test
    void plansSkillEchoToolCallFromUserMessage() {
        AssistantMessage message = ConcurrencyMockResponsePlanner.plan(List.of(
            Map.of("role", "user", "content", "skill_echo:token-42")));
        assertThat(message.getToolCalls()).hasSize(1);
        assertThat(message.getToolCalls().get(0).getName()).isEqualTo("skill_echo");
        assertThat(message.getToolCalls().get(0).getArguments()).contains("token-42");
    }

    @Test
    void plansLookupToolCallWithDelay() {
        AssistantMessage message = ConcurrencyMockResponsePlanner.plan(List.of(
            Map.of("role", "user", "content", "lookup:key-7 delayMs=20")));
        assertThat(message.getToolCalls()).hasSize(1);
        assertThat(message.getToolCalls().get(0).getName()).isEqualTo("concurrent_lookup");
        assertThat(message.getToolCalls().get(0).getArguments()).contains("key-7").contains("20");
    }

    @Test
    void summarizesToolResultForCurrentTurn() {
        AssistantMessage message = ConcurrencyMockResponsePlanner.plan(List.of(
            Map.of("role", "user", "content", "skill_echo:token-1"),
            Map.of("role", "assistant", "content", "", "tool_calls", List.of()),
            Map.of("role", "tool", "content", "ECHO:token-1;session=bench-q-0")));
        assertThat(message.getContent()).asString().contains("ECHO:token-1");
        assertThat(message.getToolCalls()).isNull();
    }
}
