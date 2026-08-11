/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.spec.dto.AgentError;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Unit tests for remote invocation batch protocol mapping.
 *
 * @since 0.1.0
 */
class RemoteInvocationBatchMapperTest {
    private final RemoteInvocationBatchMapper mapper = new RemoteInvocationBatchMapper();

    @Test
    void parseOrdersMembersAndUsesGeneratedBatchId() {
        Map<String, Object> interrupt = Map.of("items", List.of(interruptMember(2, "call-c", true),
                interruptMember(0, "call-a", true), interruptMember(1, "call-b", true)));

        RemoteInvocationBatch batch = mapper.parse(interrupt, request(), "parent-1", observer());

        assertThat(batch.batchId).isNotBlank();
        assertThat(batch.parentTaskId).isEqualTo("parent-1");
        assertThat(batch.shouldResume).isTrue();
        assertThat(batch.members).extracting(member -> member.toolCallId).containsExactly("call-a", "call-b", "call-c");
    }

    @Test
    void parseRejectsDuplicateToolCallIds() {
        Map<String, Object> interrupt = Map.of("items",
                List.of(interruptMember(0, "call-a", true), interruptMember(1, "call-a", true)));

        assertThatThrownBy(() -> mapper.parse(interrupt, request(), "parent-1", observer()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CORE_INTERRUPT_CORRELATION_CONFLICT: call-a");
    }

    @Test
    void parseRejectsMixedResumeModes() {
        Map<String, Object> interrupt = Map.of("items",
                List.of(interruptMember(0, "call-a", true), interruptMember(1, "call-b", false)));

        assertThatThrownBy(() -> mapper.parse(interrupt, request(), "parent-1", observer()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CORE_INTERRUPT_RESUME_MIXED_UNSUPPORTED");
    }

    @Test
    void restorePreservesFailureDetailsAndMemberOrder() {
        Map<String, Object> snapshot = Map.of("batchId", "batch-1", "resume", false, "members",
                List.of(snapshotMember(1, "call-b", "COMPLETED", "result-b"), snapshotMember(0, "call-a", "FAILED",
                        Map.of("code", "REMOTE_BUSINESS_FAILURE", "message", "declined"))));

        RemoteInvocationBatch batch = mapper.restore(snapshot, request(), "parent-1", observer());

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

        assertThatThrownBy(() -> mapper.restore(snapshot, request(), "parent-1", observer()))
                .isInstanceOf(IllegalStateException.class).hasMessage("REMOTE_BATCH_MEMBER_INVALID");
    }

    @Test
    void snapshotEncodesCompletedFailedAndWaitingMembers() {
        Member completed = member("call-a");
        completed.state = MemberState.COMPLETED;
        completed.remoteTaskId = "remote-a";
        completed.resultCategory = "COMPLETED";
        completed.result = "result-a";
        Member failed = member("call-b");
        failed.fail(MemberState.FAILED, "REMOTE_BUSINESS_FAILURE", "declined");
        Member waiting = member("call-c");
        waiting.state = MemberState.INPUT_REQUIRED;
        waiting.inputPrompt = "input-c";

        Map<String, Object> snapshot = mapper.snapshot(batch(List.of(completed, failed, waiting), true),
                "WAITING_INPUT");

        assertThat(snapshot).containsEntry("batchId", "batch-1").containsEntry("parentTaskId", "parent-1")
                .containsEntry("resume", true).containsEntry("state", "WAITING_INPUT");
        assertThat(snapshot.get("members")).asList().containsExactly(
                Map.of("index", 0, "toolCallId", "call-a", "toolName", "tool-call-a", "agentName", "agent-call-a",
                        "state", "COMPLETED", "remoteTaskId", "remote-a", "resultCategory", "COMPLETED", "result",
                        "result-a"),
                Map.of("index", 0, "toolCallId", "call-b", "toolName", "tool-call-b", "agentName", "agent-call-b",
                        "state", "FAILED", "resultCategory", "REMOTE_BUSINESS_FAILURE", "result",
                        Map.of("ok", false, "code", "REMOTE_BUSINESS_FAILURE", "message", "declined", "remoteAgentId",
                                "agent-call-b")),
                Map.of("index", 0, "toolCallId", "call-c", "toolName", "tool-call-c", "agentName", "agent-call-c",
                        "state", "INPUT_REQUIRED", "inputPrompt", "input-c"));
    }

    @Test
    void continuationRequestSurvivesTaskStoreSerialization() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conversation-persisted");
        request.setStream(false);
        request.setUserId("user-1");
        request.setSpaceId("space-1");
        request.setTenantId("tenant-1");
        request.setMessages(List.of(Map.of("role", "user", "content", "delegate")));
        request.setMetadata(Map.of("traceId", "trace-1"));
        RemoteInvocationBatch batch = new RemoteInvocationBatch("batch-persisted", "parent-persisted", request,
                observer(), List.of(member("call-a")), true);
        Map<String, Object> snapshot = mapper.snapshot(batch, "READY_TO_RESUME");
        Task shadow = Task.builder().id("shadow:test:parent-persisted").contextId(request.getConversationId())
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                .metadata(Map.of("_remote_batch", snapshot)).build();

        Task restoredShadow = JsonUtil.OBJECT_MAPPER.fromJson(JsonUtil.OBJECT_MAPPER.toJson(shadow), Task.class);
        Map<?, ?> restoredSnapshot = (Map<?, ?>) restoredShadow.metadata().get("_remote_batch");
        ServeRequest restored = mapper.continuationRequest(restoredSnapshot, new ServeRequest());

        assertThat(restored.getConversationId()).isEqualTo("conversation-persisted");
        assertThat(restored.isStream()).isFalse();
        assertThat(restored.getUserId()).isEqualTo("user-1");
        assertThat(restored.getSpaceId()).isEqualTo("space-1");
        assertThat(restored.getTenantId()).isEqualTo("tenant-1");
        assertThat(restored.getMessages()).containsExactly(Map.of("role", "user", "content", "delegate"));
        assertThat(restored.getMetadata()).containsExactlyEntriesOf(Map.of("traceId", "trace-1"));
    }

    @Test
    void resolutionMapsReadyResultsAndWaitingInterrupts() {
        Member completed = member("call-a");
        completed.state = MemberState.COMPLETED;
        completed.result = "result-a";
        Member failed = member("call-b");
        failed.fail(MemberState.TIMED_OUT, "REMOTE_TIMEOUT", "Remote invocation timed out");

        RemoteInvocationBatchCoordinator.BatchResolution ready = mapper
                .resolution(batch(List.of(completed, failed), false));

        assertThat(ready.isReadyToResume()).isTrue();
        assertThat(ready.shouldResume()).isFalse();
        assertThat(ready.results()).containsEntry("call-a", "result-a");
        assertThat(ready.results().get("call-b")).isEqualTo(Map.of("ok", false, "code", "REMOTE_TIMEOUT", "message",
                "Remote invocation timed out", "remoteAgentId", "agent-call-b"));

        Member waiting = member("call-c");
        waiting.state = MemberState.INPUT_REQUIRED;
        waiting.inputPrompt = "input-c";
        RemoteInvocationBatchCoordinator.BatchResolution interrupted = mapper.resolution(batch(List.of(waiting), true));

        assertThat(interrupted.isReadyToResume()).isFalse();
        assertThat(interrupted.results()).isEmpty();
        assertThat(interrupted.interrupt()).containsEntry("message", "input-c");
        assertThat(interrupted.interrupt().get("items")).asList()
                .containsExactly(Map.of("toolCallId", "call-c", "toolName", "tool-call-c", "message", "input-c"));
    }

    @Test
    void applyOutcomeMapsRemoteStates() {
        Member completed = member("call-a");
        mapper.applyOutcome(completed,
                new RemoteCallOutcome("remote-a", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-a", null), null);
        assertThat(completed.state).isEqualTo(MemberState.COMPLETED);
        assertThat(completed.remoteTaskId).isEqualTo("remote-a");
        assertThat(completed.result).isEqualTo("result-a");

        Member waiting = member("call-b");
        mapper.applyOutcome(waiting, new RemoteCallOutcome("remote-b", TaskState.TASK_STATE_AUTH_REQUIRED,
                "INPUT_REQUIRED", null, "authenticate"), null);
        assertThat(waiting.state).isEqualTo(MemberState.INPUT_REQUIRED);
        assertThat(waiting.inputPrompt).isEqualTo("authenticate");

        Member failed = member("call-c");
        mapper.applyOutcome(failed, new RemoteCallOutcome("remote-c", TaskState.TASK_STATE_FAILED,
                "REMOTE_BUSINESS_FAILURE", "declined", null), null);
        assertThat(failed.state).isEqualTo(MemberState.FAILED);
        assertThat(failed.resultCategory).isEqualTo("REMOTE_BUSINESS_FAILURE");
        assertThat(failed.errorMessage).isEqualTo("declined");
    }

    @Test
    void applyOutcomeClassifiesTransportFailures() {
        assertFailure(new CompletionException(new TimeoutException("late")), MemberState.TIMED_OUT, "REMOTE_TIMEOUT");
        assertFailure(new RejectedExecutionException("queue full"), MemberState.FAILED, "REMOTE_OVERLOADED");
        assertFailure(new IllegalStateException("HTTP 429 rate limit"), MemberState.FAILED, "REMOTE_RATE_LIMITED");
        assertFailure(new IllegalArgumentException("malformed JSON-RPC response"), MemberState.FAILED,
                "REMOTE_PROTOCOL_ERROR");
        assertFailure(new IllegalStateException("connection refused"), MemberState.FAILED, "REMOTE_UNAVAILABLE");
    }

    @Test
    void callbackOutcomeUsesOnlyMarkedTerminalArtifact() {
        Artifact textProgress = new Artifact("artifact-text-progress", null, null,
                List.of(new TextPart("intermediate text")), Map.of(), List.of());
        Artifact traceProgress = new Artifact("artifact-trace-progress", null, null,
                List.of(new DataPart(Map.of("type", "trace", "payload", Map.of("content", "reasoning")))), Map.of(),
                List.of());
        Artifact answer = new Artifact("artifact-answer", null, null, List.of(new TextPart("final answer")),
                Map.of("_agentcore_terminal", true), List.of());
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(textProgress, traceProgress, answer)).build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);

        assertThat(outcome.result()).isEqualTo("final answer");
    }

    @Test
    void callbackOutcomeUsesStatusMessageWhenCompletedTaskHasNoArtifacts() {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart("status result")).build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, message, null)).artifacts(List.of()).build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);

        assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(outcome.result()).isEqualTo("status result");
    }

    @Test
    void callbackOutcomeUsesFailureStatusBeforeArtifacts() {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart("declined")).build();
        Artifact artifact = Artifact.builder().artifactId("artifact-premature").parts(new TextPart("premature"))
                .build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_FAILED, message, null)).artifacts(List.of(artifact))
                .build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);

        assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(outcome.result()).isEqualTo("declined");
    }

    @Test
    void callbackOutcomeTreatsWorkingSnapshotWithResultAsCompleted() {
        Artifact artifact = Artifact.builder().artifactId("artifact-result").parts(new TextPart("callback result"))
                .metadata(Map.of("_agentcore_terminal", true)).build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).artifacts(List.of(artifact)).build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);

        assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(outcome.result()).isEqualTo("callback result");
    }

    @Test
    void callbackOutcomeClassifiesMissingStatusAsProtocolError() {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("remote-task");
        when(task.artifacts()).thenReturn(List.of());

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);

        assertThat(outcome.remoteState()).isNull();
        assertThat(outcome.resultCategory()).isEqualTo("REMOTE_PROTOCOL_ERROR");
        assertThat(outcome.result()).isEmpty();
    }

    @Test
    void callbackOutcomeKeepsCoarseAndSpecificErrors() {
        AgentError remoteError = new AgentError("MODEL_CALL_FAILED", 181001, false, "AGENT_CORE");
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart("model failed")))
                .metadata(Map.of(AgentError.METADATA_KEY, remoteError.toMap())).build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_FAILED, message, null)).build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);
        Member member = member("call-a");
        mapper.applyOutcome(member, outcome, null);
        Object toolResult = mapper.resolution(batch(List.of(member), true)).results().get("call-a");

        assertThat(outcome.resultCategory()).isEqualTo("REMOTE_BUSINESS_FAILURE");
        assertThat(outcome.remoteError()).isEqualTo(remoteError);
        assertThat(toolResult).isInstanceOfSatisfying(Map.class, result -> {
            assertThat(result).containsEntry("code", "REMOTE_BUSINESS_FAILURE");
            assertThat(result).containsEntry("remoteError", remoteError.toMap());
        });
    }

    @Test
    void legacyFailedCallbackKeepsTextOnlyFallback() {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart("declined")))
                .build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_FAILED, message, null)).build();

        RemoteCallOutcome outcome = mapper.callbackOutcome(task);
        Member member = member("call-a");
        mapper.applyOutcome(member, outcome, null);

        assertThat(outcome.remoteError()).isNull();
        assertThat(mapper.resolution(batch(List.of(member), true)).results().get("call-a"))
                .isEqualTo(Map.of("ok", false, "code", "REMOTE_BUSINESS_FAILURE", "message", "declined",
                        "remoteAgentId", "agent-call-a"));
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

    private static Member member(String toolCallId) {
        return new Member(0, toolCallId, "tool-" + toolCallId, "agent-" + toolCallId, "message-" + toolCallId);
    }

    private static RemoteInvocationBatch batch(List<Member> members, boolean shouldResume) {
        return new RemoteInvocationBatch("batch-1", "parent-1", request(), observer(), members, shouldResume);
    }

    private void assertFailure(Throwable error, MemberState state, String category) {
        Member member = member("call-failure");

        mapper.applyOutcome(member, null, error);

        assertThat(member.state).isEqualTo(state);
        assertThat(member.resultCategory).isEqualTo(category);
        assertThat(member.completedAt).isNotNull();
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
