/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiuwenCoreExtAgentHandlerTest {

    @Test
    void extractResumeInputFromMessageField() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(Map.of("resume_input", "approved")));

        assertThat(JiuwenCoreExtAgentHandler.extractResumeInput(request)).isEqualTo("approved");
    }

    @Test
    void extractResumeInputFromMetadata() {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(Map.of("metadata", Map.of("resume_input", "Alice"))));

        assertThat(JiuwenCoreExtAgentHandler.extractResumeInput(request)).isEqualTo("Alice");
    }

    @Test
    void buildInterruptAwareInputsAttachesResumeKey() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.setMessages(List.of(Map.of("resume_input", "go")));

        Map<String, Object> inputs = JiuwenCoreExtAgentHandler.buildInterruptAwareInputs(request);

        assertThat(inputs.get(ToolInterruptionState.RESUME_USER_INPUT_KEY)).isEqualTo("go");
        assertThat(inputs.get("conversation_id")).isEqualTo("c1");
    }

    @Test
    void interruptPayloadDetection() {
        Map<String, Object> interrupt = new java.util.LinkedHashMap<>();
        interrupt.put("type", "interrupt");
        assertThat(InterruptEventMapper.isInterruptPayload(interrupt)).isTrue();

        Map<String, Object> inputRequired = Map.of("state", "INPUT_REQUIRED");
        assertThat(InterruptEventMapper.isInterruptPayload(inputRequired)).isTrue();

        assertThat(InterruptEventMapper.isInterruptPayload(Map.of("type", "chunk"))).isFalse();
    }
}
