/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResetConversationRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesPythonAlignedFields() throws Exception {
        ResetConversationRequest request = mapper.readValue("{\"conversation_id\":\"conv-1\",\"user_id\":\"u1\"}",
                ResetConversationRequest.class);

        assertThat(request.getConversationId()).isEqualTo("conv-1");
        assertThat(request.getUserId()).isEqualTo("u1");
    }
}
