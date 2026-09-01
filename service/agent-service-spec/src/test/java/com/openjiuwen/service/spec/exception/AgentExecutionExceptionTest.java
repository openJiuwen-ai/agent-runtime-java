/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.exception;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import org.junit.jupiter.api.Test;

/**
 * Tests the structured agent failure contract.
 *
 * @since 0.1.0
 */
class AgentExecutionExceptionTest {
    @Test
    void requiresStableCodeAndDescriptor() {
        assertThatThrownBy(() -> new AgentFailureDescriptor(" ", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("code must not be blank");
        assertThatThrownBy(() -> new AgentExecutionException("failed", null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("descriptor must not be null");
    }
}
