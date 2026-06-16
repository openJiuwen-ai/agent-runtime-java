/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.reset;

import com.openjiuwen.service.spec.dto.ResetConversationRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResetIngressSupportTest {

    @Test
    void validConversationId() {
        ResetConversationRequest request = new ResetConversationRequest();
        request.setConversationId("conv-1");

        ResetIngressSupport.ValidationResult result = ResetIngressSupport.validate(request);

        assertThat(result.valid()).isTrue();
        assertThat(result.conversationId()).isEqualTo("conv-1");
    }

    @Test
    void missingOrBlankConversationId() {
        assertInvalid(ResetIngressSupport.validate(null));
        assertInvalid(ResetIngressSupport.validate(new ResetConversationRequest()));

        ResetConversationRequest blank = new ResetConversationRequest();
        blank.setConversationId("   ");
        assertInvalid(ResetIngressSupport.validate(blank));
    }

    private static void assertInvalid(ResetIngressSupport.ValidationResult result) {
        assertThat(result.valid()).isFalse();
        assertThat(result.errorStatus()).isEqualTo(400);
        assertThat(result.errorBody()).containsEntry("type", "error");
        assertThat(result.errorBody()).containsEntry("error", "conversation_id is required");
    }
}
