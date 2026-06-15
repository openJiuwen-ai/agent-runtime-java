/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesPythonStyleSnakeCaseFields() throws Exception {
        String json = """
                {
                  "conversation_id": "conv-1",
                  "messages": [{"role": "user", "content": "hello"}],
                  "user_id": "u1",
                  "space_id": "s1",
                  "stream": true
                }
                """;

        QueryRequest req = mapper.readValue(json, QueryRequest.class);

        assertThat(req.getConversationId()).isEqualTo("conv-1");
        assertThat(req.getUserId()).isEqualTo("u1");
        assertThat(req.getSpaceId()).isEqualTo("s1");
        assertThat(req.isStream()).isTrue();
        assertThat(req.getMessages()).hasSize(1);
        assertThat(req.getMessages().get(0)).containsEntry("role", "user");
    }

    @Test
    void singleMessageFieldNormalizesToMessages() {
        QueryRequest req = new QueryRequest();
        req.setConversationId("c1");
        req.setMessage("hi there");
        req.normalizeMessages();

        assertThat(req.getMessages()).hasSize(1);
        assertThat(req.getMessages().get(0)).containsEntry("role", "user");
        assertThat(req.getMessages().get(0)).containsEntry("content", "hi there");
    }

    @Test
    void serveRequestFromQueryRequestPreservesFields() {
        QueryRequest req = new QueryRequest();
        req.setConversationId("c2");
        req.setMessages(List.of(Map.of("role", "user", "content", "q")));
        req.setUserId("alice");
        req.setSpaceId("sp");
        req.setTenantId("t1");
        req.setStream(false);

        ServeRequest serve = ServeRequest.fromQueryRequest(req);

        assertThat(serve.getConversationId()).isEqualTo("c2");
        assertThat(serve.getUserId()).isEqualTo("alice");
        assertThat(serve.getSpaceId()).isEqualTo("sp");
        assertThat(serve.getTenantId()).isEqualTo("t1");
        assertThat(serve.isStream()).isFalse();
        assertThat(serve.lastUserQuery()).isEqualTo("q");
    }
}
