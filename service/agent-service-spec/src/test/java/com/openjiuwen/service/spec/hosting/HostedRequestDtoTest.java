/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.ResetConversationRequest;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

/**
 * Verifies optional instance routing fields on existing request DTOs.
 *
 * @since 0.1.2
 */
class HostedRequestDtoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyStringOrNullWithoutCoercion() throws Exception {
        for (Class<?> dto : new Class<?>[] {QueryRequest.class, ResetConversationRequest.class}) {
            assertThat(mapper.readTree(mapper.writeValueAsString(mapper.readValue("{\"agent_id\":\"B\"}", dto)))
                    .get("agent_id").asText()).isEqualTo("B");
            for (String invalid : new String[] {"12", "true", "[]", "{}"}) {
                assertThatThrownBy(() -> mapper.readValue("{\"agent_id\":" + invalid + "}", dto))
                        .isInstanceOf(JsonMappingException.class);
            }
            assertThat(mapper.readTree(mapper.writeValueAsString(mapper.readValue("{\"agent_id\":null}", dto)))
                    .has("agent_id")).isFalse();
        }
    }

    @Test
    void preservesLegacyOutputAndDoesNotPropagateSelectionToServeRequest() throws Exception {
        QueryRequest request = mapper.readValue(
                "{\"agent_id\":\"B\",\"conversation_id\":\"原会话:c/1\",\"message\":\"hello\"}", QueryRequest.class);
        ServeRequest serve = ServeRequest.fromQueryRequest(request);
        assertThat(serve.getConversationId()).isEqualTo("原会话:c/1");
        assertThat(mapper.writeValueAsString(serve)).doesNotContain("agent_id", "agentId");
        assertThat(mapper.writeValueAsString(new QueryRequest())).doesNotContain("agent_id", "agentId");
        assertThat(mapper.writeValueAsString(new ResetConversationRequest())).doesNotContain("agent_id", "agentId");
        assertThat(mapper.readValue("{\"agentId\":\"B\"}", QueryRequest.class).getAgentId()).isNull();
    }
}
