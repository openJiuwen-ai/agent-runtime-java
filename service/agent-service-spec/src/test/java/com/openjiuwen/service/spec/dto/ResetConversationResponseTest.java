/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResetConversationResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void okFactoryAndJsonShape() throws Exception {
        ResetConversationResponse response = ResetConversationResponse.ok("conv-1");

        assertThat(response.getStatus()).isEqualTo("ok");
        assertThat(response.getMessage()).isEqualTo("Conversation conv-1 reset");

        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(response), Map.class);
        assertThat(json).containsEntry("status", "ok");
        assertThat(json).containsEntry("message", "Conversation conv-1 reset");
        assertThat(json).doesNotContainKey("result");
    }

    @Test
    void exposesResetPathConstants() {
        assertThat(AgentServicePaths.RESET_CONVERSATION_V1).isEqualTo("/v1/reset_conversation");
        assertThat(AgentServicePaths.RESET_CONVERSATION_LEGACY).isEqualTo("/reset_conversation");
    }
}
