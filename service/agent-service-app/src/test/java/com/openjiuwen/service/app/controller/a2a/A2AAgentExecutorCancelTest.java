/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

class A2AAgentExecutorCancelTest {
    @Test
    void theCancelChunkTypeIsDistinctFromEveryOtherType() {
        assertThat(QueryChunk.TYPE_CANCEL).isEqualTo("cancel");
        assertThat(QueryChunk.TYPE_CANCEL).isNotEqualTo(QueryChunk.TYPE_CHUNK)
                .isNotEqualTo(QueryChunk.TYPE_INTERRUPT).isNotEqualTo(QueryChunk.TYPE_ERROR)
                .isNotEqualTo(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT);
    }

    @Test
    void aCancelChunkDefaultsToTheCancelType() {
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CANCEL, Map.of("reason", "user cancelled"));

        assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_CANCEL);
        assertThat(chunk.getData()).isEqualTo(Map.of("reason", "user cancelled"));
    }

    @Test
    void theReasonIsReadFromTextAndFromAReasonFieldAndIsOptional() throws Exception {
        Method cancelReason = A2AAgentExecutor.class.getDeclaredMethod("cancelReason", Object.class);
        cancelReason.setAccessible(true);

        assertThat(cancelReason.invoke(null, "user cancelled")).isEqualTo("user cancelled");
        assertThat(cancelReason.invoke(null, Map.of("reason", "policy"))).isEqualTo("policy");
        assertThat(cancelReason.invoke(null, (Object) null)).isEqualTo("");
        assertThat(cancelReason.invoke(null, Map.of("other", "x"))).isEqualTo("");
        assertThat(cancelReason.invoke(null, 42)).isEqualTo("");
    }
}
