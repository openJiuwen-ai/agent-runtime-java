/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.examples;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EchoProxyAgentHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void queryPrefixesUserMessage() {
        EchoProxyAgentHandler handler = new EchoProxyAgentHandler();
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.setMessages(List.of(Map.of("role", "user", "content", "hello")));

        QueryResponse response = handler.query(request);

        assertThat(response.getConversationId()).isEqualTo("c1");
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("content", "proxy:hello");
        assertThat(result).containsEntry("handler", "echo-proxy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customPrefixIsApplied() {
        EchoProxyAgentHandler handler = new EchoProxyAgentHandler("custom:");
        ServeRequest request = new ServeRequest();
        request.setConversationId("c2");
        request.setMessages(List.of(Map.of("role", "user", "content", "ping")));

        Map<String, Object> result = (Map<String, Object>) handler.query(request).getResult();
        assertThat(result).containsEntry("content", "custom:ping");
    }
}
