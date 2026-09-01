/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for skill echo rail argument parsing.
 *
 * @since 0.1.0
 */
class SkillEchoRailTest {
    @Test
    void extractTokenReadsJsonArgument() {
        ToolCall toolCall = ToolCall.builder().name("skill_echo").arguments("{\"token\":\"bench-42\"}").build();
        assertThat(SkillEchoRail.extractToken(toolCall)).isEqualTo("bench-42");
    }

    @Test
    void extractTokenFallsBackWhenMissing() {
        ToolCall toolCall = ToolCall.builder().name("skill_echo").arguments("{}").build();
        assertThat(SkillEchoRail.extractToken(toolCall)).isEqualTo("missing-token");
    }
}
