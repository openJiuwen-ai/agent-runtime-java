/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for parsing and restoring remote invocation batches.
 *
 * @since 0.1.0
 */
class RemoteInvocationBatchParserTest {
    private final RemoteInvocationBatchParser parser = new RemoteInvocationBatchParser();

    @Test
    void parseOrdersMembersAndUsesGeneratedBatchId() {
        Map<String, Object> interrupt = Map.of("items", List.of(interruptMember(2, "call-c", true),
                interruptMember(0, "call-a", true), interruptMember(1, "call-b", true)));

        RemoteInvocationBatch batch = parser.parse(interrupt, request(), "parent-1", observer());

        assertThat(batch.batchId).isNotBlank();
        assertThat(batch.parentTaskId).isEqualTo("parent-1");
        assertThat(batch.shouldResume).isTrue();
        assertThat(batch.members).extracting(member -> member.toolCallId).containsExactly("call-a", "call-b", "call-c");
    }

    @Test
    void parseRejectsDuplicateToolCallIds() {
        Map<String, Object> interrupt = Map.of("items",
                List.of(interruptMember(0, "call-a", true), interruptMember(1, "call-a", true)));

        assertThatThrownBy(() -> parser.parse(interrupt, request(), "parent-1", observer()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CORE_INTERRUPT_CORRELATION_CONFLICT: call-a");
    }

    @Test
    void parseRejectsMixedResumeModes() {
        Map<String, Object> interrupt = Map.of("items",
                List.of(interruptMember(0, "call-a", true), interruptMember(1, "call-b", false)));

        assertThatThrownBy(() -> parser.parse(interrupt, request(), "parent-1", observer()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CORE_INTERRUPT_RESUME_MIXED_UNSUPPORTED");
    }

    @Test
    void restorePreservesFailureDetailsAndMemberOrder() {
        Map<String, Object> snapshot = Map.of("batchId", "batch-1", "resume", false, "members",
                List.of(snapshotMember(1, "call-b", "COMPLETED", "result-b"), snapshotMember(0, "call-a", "FAILED",
                        Map.of("code", "REMOTE_BUSINESS_FAILURE", "message", "declined"))));

        RemoteInvocationBatch batch = parser.restore(snapshot, request(), "parent-1", observer());

        assertThat(batch.batchId).isEqualTo("batch-1");
        assertThat(batch.shouldResume).isFalse();
        assertThat(batch.members).extracting(member -> member.toolCallId).containsExactly("call-a", "call-b");
        assertThat(batch.members.get(0).state).isEqualTo(MemberState.FAILED);
        assertThat(batch.members.get(0).resultCategory).isEqualTo("REMOTE_BUSINESS_FAILURE");
        assertThat(batch.members.get(0).errorMessage).isEqualTo("declined");
    }

    @Test
    void restoreRejectsInvalidMemberEntry() {
        Map<String, Object> snapshot = Map.of("members", List.of("not-a-member"));

        assertThatThrownBy(() -> parser.restore(snapshot, request(), "parent-1", observer()))
                .isInstanceOf(IllegalStateException.class).hasMessage("REMOTE_BATCH_MEMBER_INVALID");
    }

    private static Map<String, Object> interruptMember(int index, String toolCallId, boolean shouldResume) {
        return Map.of("index", index, "toolCallId", toolCallId, "toolName", "tool-" + toolCallId, "message",
                "message-" + toolCallId, "context",
                Map.of("_interrupt_kind", "a2a_delegate", "agentName", "agent-" + toolCallId, "resume", shouldResume));
    }

    private static Map<String, Object> snapshotMember(int index, String toolCallId, String state, Object result) {
        return Map.of("index", index, "toolCallId", toolCallId, "toolName", "tool-" + toolCallId, "agentName",
                "agent-" + toolCallId, "state", state, "result", result);
    }

    private static ServeRequest request() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conversation-1");
        request.setMetadata(Map.of());
        return request;
    }

    private static SerialQueryStreamObserver observer() {
        return new SerialQueryStreamObserver(mock(QueryStreamObserver.class));
    }
}
