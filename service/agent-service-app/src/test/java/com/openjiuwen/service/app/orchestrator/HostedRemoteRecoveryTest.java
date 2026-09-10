/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Verifies instance-local recovery and unchanged remote task correlation.
 *
 * @since 0.1.2
 */
class HostedRemoteRecoveryTest {
    private final RemoteInvocationDispatcher dispatcher = new RemoteInvocationDispatcher(2, 4, Duration.ofSeconds(30));

    @Test
    void sameRemoteTaskResumesOnlyItsParentWithIndependentClaims() {
        Target a = target("a", new InMemoryTaskStore());
        Target b = target("b", new InMemoryTaskStore());
        waitForInput(a);
        waitForInput(b);
        Task originalA = shadow(a);

        assertThat(b.coordinator.recoverCallback(completedTask("result-b"))).isTrue();
        assertThat(a.continuations).isEmpty();
        assertThat(shadow(a)).isSameAs(originalA);
        assertThat(b.continuations).hasSize(1);
        assertReady(b, "result-b");
        assertThat(b.coordinator.recoverCallback(completedTask("result-b"))).isTrue();
        assertThat(b.continuations).hasSize(1);

        assertThat(a.coordinator.recoverCallback(completedTask("result-a"))).isTrue();
        assertReady(a, "result-a");
        String claimId = assertInstanceOf(String.class, snapshot(a).get("batchId"));
        assertThat(a.coordinator.claimCoreResume(request(), claimId)).isTrue();
        assertThat(b.coordinator.claimCoreResume(request(), claimId)).isTrue();
        assertThat(a.coordinator.claimCoreResume(request(), claimId)).isFalse();
        ServeRequest completion = request();
        completion.setMetadata(Map.of("runtime.parentTaskId", "same-parent", "runtime.remoteBatchId", claimId,
                "runtime.remoteToolResults", Map.of("same-call", "result-a")));
        a.coordinator.completeResume(completion);
        assertThat(shadow(a)).isNull();
        assertThat(shadow(b)).isNotNull();
        assertThat(a.coordinator.claimCoreResume(request(), claimId)).isTrue();
        assertThat(b.coordinator.claimCoreResume(request(), claimId)).isFalse();
    }

    @Test
    void rebuiltCoordinatorsRecoverOnlyFromTheirOwnSerializedSnapshots() throws Exception {
        Target originalA = target("a", new InMemoryTaskStore());
        Target originalB = target("b", new InMemoryTaskStore());
        waitForInput(originalA);
        waitForInput(originalB);
        var restoredA = restore(originalA);
        var restoredB = restore(originalB);

        assertThat(restoredB.coordinator.recoverCallback(completedTask("after-restart-b"))).isTrue();
        assertReady(restoredB, "after-restart-b");
        assertThat(restoredA.continuations).isEmpty();
        assertThat(snapshot(restoredA).get("state")).isEqualTo("WAITING_INPUT");
        assertThat(originalA.continuations).isEmpty();
        assertThat(originalB.continuations).isEmpty();
        assertThat(restoredA.coordinator.recoverCallback(completedTask("after-restart-a"))).isTrue();
        assertReady(restoredA, "after-restart-a");
        assertThat(restoredA.calls).isEmpty();
        assertThat(restoredB.calls).isEmpty();
    }

    @Test
    void earlyCallbackIsReplayedOnlyByTheInstanceThatReceivedIt() {
        Target a = target("a", new InMemoryTaskStore());
        Target b = target("b", new InMemoryTaskStore());
        var initialA = execute(a);
        var initialB = execute(b);
        assertThat(a.coordinator.recoverCallback(completedTask("early-a"))).isFalse();
        b.outcomes.get(0).complete(waiting());
        initialB.join();
        assertThat(b.continuations).isEmpty();
        assertThat(snapshot(b).get("state")).isEqualTo("WAITING_INPUT");
        a.outcomes.get(0).complete(waiting());
        initialA.join();
        assertReady(a, "early-a");
        assertThat(b.continuations).isEmpty();
    }

    @Test
    void toolInputPreservesRemoteTaskContextAndRegistrationBoundary() {
        Target a = target("a", new InMemoryTaskStore());
        Target b = target("b", new InMemoryTaskStore());
        waitForInput(a);
        waitForInput(b);
        var resumeA = resumeInput(a, "answer-a");
        var resumeB = resumeInput(b, "answer-b");
        for (Target target : List.of(a, b)) {
            assertThat(target.calls).hasSize(2);
            assertThat(target.calls.get(0).contextId()).isEqualTo("same-conversation");
            assertThat(target.calls.get(1).contextId()).isEqualTo("same-conversation");
            assertThat(target.calls.get(1).taskId()).isEqualTo("same-remote-task");
            assertThat(target.calls.get(1).message()).isEqualTo("answer-" + target.id);
        }
        b.outcomes.get(1).complete(completed("resumed-b"));
        assertThat(resumeB.join().results()).containsEntry("same-call", "resumed-b");
        assertThat(resumeA).isNotDone();
        a.outcomes.get(1).complete(completed("resumed-a"));
        assertThat(resumeA.join().results()).containsEntry("same-call", "resumed-a");
    }

    private Target target(String id, InMemoryTaskStore store) {
        var calls = new ArrayList<RemoteCall>();
        var outcomes = new ArrayList<CompletableFuture<RemoteCallOutcome>>();
        var continuations = new ArrayList<ServeRequest>();
        RemoteAgentCaller caller = (call, observer) -> {
            calls.add(call);
            var outcome = new CompletableFuture<RemoteCallOutcome>();
            outcomes.add(outcome);
            return outcome;
        };
        var coordinator = new RemoteInvocationBatchCoordinator(store, caller, id, dispatcher, continuations::add);
        return new Target(id, coordinator, store, calls, outcomes, continuations);
    }

    private Target restore(Target original) throws Exception {
        var store = new InMemoryTaskStore();
        for (Task task : original.store.list(ListTasksParams.builder().build()).tasks()) {
            store.save(JsonUtil.fromJson(JsonUtil.toJson(task), Task.class), true);
        }
        return target(original.id, store);
    }

    private static void waitForInput(Target target) {
        var initial = execute(target);
        target.outcomes.get(0).complete(waiting());
        initial.join();
        assertThat(snapshot(target).get("state")).isEqualTo("WAITING_INPUT");
    }

    private static CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> execute(Target target) {
        Map<String, Object> item = Map.of("index", 0, "toolCallId", "same-call", "toolName", "remote",
                "message", "hello", "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "remote"));
        return target.coordinator.execute(Map.of("batchId", "same-batch", "items", List.of(item)), request(),
                mock(QueryStreamObserver.class));
    }

    private static CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> resumeInput(Target target,
            String input) {
        ServeRequest request = request();
        var metadata = new LinkedHashMap<>(request.getMetadata());
        metadata.put("runtime.remoteToolInputs", Map.of("same-call", input));
        request.setMetadata(metadata);
        return target.coordinator.resume(request, mock(QueryStreamObserver.class)).orElseThrow();
    }

    private static void assertReady(Target target, String result) {
        assertThat(target.continuations).hasSize(1);
        ServeRequest request = target.continuations.get(0);
        assertThat(request.getConversationId()).isEqualTo("same-conversation");
        assertThat(request.getMetadata()).containsEntry("runtime.parentTaskId", "same-parent")
                .containsEntry("runtime.remoteBatchId", snapshot(target).get("batchId"));
        var resolution = target.coordinator.resume(request, mock(QueryStreamObserver.class)).orElseThrow().join();
        assertThat(resolution.isReadyToResume()).isTrue();
        assertThat(resolution.results()).containsOnly(Map.entry("same-call", result));
    }

    private static ServeRequest request() {
        var request = new ServeRequest();
        request.setConversationId("same-conversation");
        request.setMetadata(Map.of("runtime.parentTaskId", "same-parent"));
        return request;
    }

    private static Task shadow(Target target) {
        return target.store.get("shadow:" + target.id + ":same-parent");
    }

    private static Map<?, ?> snapshot(Target target) {
        return (Map<?, ?>) shadow(target).metadata().get("_remote_batch");
    }

    private static RemoteCallOutcome waiting() {
        return new RemoteCallOutcome("same-remote-task", TaskState.TASK_STATE_INPUT_REQUIRED, "INPUT_REQUIRED",
                null, "provide input");
    }

    private static RemoteCallOutcome completed(String text) {
        return new RemoteCallOutcome("same-remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", text, null);
    }

    private static Task completedTask(String text) {
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).messageId("same-message")
                .parts(List.of(new TextPart(text))).build();
        return Task.builder().id("same-remote-task").contextId("same-conversation")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, message, null)).build();
    }

    private record Target(String id, RemoteInvocationBatchCoordinator coordinator, InMemoryTaskStore store,
            List<RemoteCall> calls, List<CompletableFuture<RemoteCallOutcome>> outcomes,
            List<ServeRequest> continuations) {
    }
}
