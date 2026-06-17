/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.external.VersatileHttpClient;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersatileAgentHandlerTest {

    @Test
    void queryMapsRemoteResult() throws Exception {
        VersatileProperties properties = new VersatileProperties();
        properties.setBaseUrl("http://localhost:8080");
        properties.setWorkflowId("wf-1");

        VersatileHttpClient client = new VersatileHttpClient(properties) {
            @Override
            public Map<String, Object> postQuery(Map<String, Object> body) {
                assertThat(body.get("workflow_id")).isEqualTo("wf-1");
                assertThat(body.get("conversation_id")).isEqualTo("c1");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("role", "assistant");
                result.put("content", "low-code-reply");
                return Map.of("result", result);
            }
        };

        VersatileAgentHandler handler = new VersatileAgentHandler(client, properties);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.setUserId("u1");
        request.setSpaceId("s1");
        request.setMessages(List.of(Map.of("role", "user", "content", "hello")));

        QueryResponse response = handler.query(request);

        assertThat(response.getConversationId()).isEqualTo("c1");
        assertThat(response.getResult()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getResult()).get("content")).isEqualTo("low-code-reply");
    }
}
