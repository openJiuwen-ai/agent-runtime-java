/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies per-target cancellation under shared remote concurrency limits.
 *
 * @since 0.1.2
 */
class HostedRemoteInvocationDispatcherTest {
    @Test
    void fourInstancesShareOneQueueAndDispatchBackToOriginalStore() throws Exception {
        var dispatcher = new RemoteInvocationDispatcher(1, 2, Duration.ofSeconds(30));
        List<String> started = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d")) {
            targets.add(target(id, dispatcher, started));
        }
        var a = execute(targets.get(0));
        assertThat(a).isNotDone();
        var b = execute(targets.get(1));
        assertThat(b).isNotDone();
        var c = execute(targets.get(2));
        assertThat(c).isNotDone();
        var d = execute(targets.get(3));
        assertThat(started).containsExactly("a");
        assertThat(d.isDone()).isTrue();
        assertThat(d.join().results().toString()).contains("REMOTE_OVERLOADED");
        targets.get(0).outcome().complete(completed("A"));
        assertThat(started).containsExactly("a", "b");
        assertThat(a.get(1, TimeUnit.SECONDS).results().values()).containsExactly("A");
        targets.get(1).outcome().complete(completed("B"));
        assertThat(started).containsExactly("a", "b", "c");
        assertThat(b.get(1, TimeUnit.SECONDS).results().values()).containsExactly("B");
        targets.get(2).outcome().complete(completed("C"));
        assertThat(c.get(1, TimeUnit.SECONDS).results().values()).containsExactly("C");
        for (int index = 0; index < 3; index++) {
            var tasks = targets.get(index).store().list(ListTasksParams.builder().build()).tasks();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).metadata().toString()).contains(String.valueOf((char) ('A' + index)));
        }
    }

    @Test
    void stoppingQueuedTargetKeepsOtherTasksAndRunningSlot() {
        var dispatcher = new RemoteInvocationDispatcher(1, 3, Duration.ofSeconds(30));
        List<String> started = new ArrayList<>();
        Target a = target("a", dispatcher, started);
        Target b = target("b", dispatcher, started);
        Target c = target("c", dispatcher, started);
        var first = execute(a);
        var second = execute(b);
        var third = execute(c);
        b.coordinator().stopDispatching();
        assertThat(second.isDone()).isTrue();
        assertThat(second.join().results().toString()).contains("REMOTE_CANCELLED");
        a.coordinator().stopDispatching();
        assertThat(first.isDone()).isFalse();
        assertThat(started).containsExactly("a");
        a.outcome().complete(completed("A"));
        assertThat(started).containsExactly("a", "c");
        c.outcome().complete(completed("C"));
        assertThat(third.join().results().values()).containsExactly("C");
    }

    @Test
    void expiredQueuedWorkSettlesInItsOwnInstance() throws Exception {
        var dispatcher = new RemoteInvocationDispatcher(1, 2, Duration.ofMillis(20));
        List<String> started = new ArrayList<>();
        Target a = target("a", dispatcher, started);
        Target b = target("b", dispatcher, started);
        execute(a);
        var queued = execute(b);
        assertThat(queued.get(2, TimeUnit.SECONDS).results().toString()).contains("REMOTE_OVERLOADED");
        assertThat(started).containsExactly("a");
        a.outcome().complete(completed("A"));
        assertThat(started).containsExactly("a");
    }

    private static Target target(String id, RemoteInvocationDispatcher dispatcher, List<String> started) {
        var outcome = new CompletableFuture<RemoteCallOutcome>();
        var store = new InMemoryTaskStore();
        RemoteAgentCaller caller = (call, observer) -> {
            started.add(id);
            return outcome;
        };
        var coordinator = new RemoteInvocationBatchCoordinator(store, caller, id, dispatcher, request -> { });
        return new Target(coordinator, store, outcome);
    }

    private static CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> execute(Target target) {
        var request = new ServeRequest();
        request.setConversationId("same-conversation");
        request.setMetadata(Map.of("runtime.parentTaskId", "same-parent"));
        Map<String, Object> item = Map.of("index", 0, "toolCallId", "same-call", "toolName", "remote",
                "message", "hello", "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "remote"));
        return target.coordinator().execute(Map.of("batchId", "same-batch", "items", List.of(item)), request,
                mock(QueryStreamObserver.class));
    }

    private static RemoteCallOutcome completed(String text) {
        return new RemoteCallOutcome("same-remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", text, null);
    }

    private record Target(RemoteInvocationBatchCoordinator coordinator, InMemoryTaskStore store,
            CompletableFuture<RemoteCallOutcome> outcome) {
    }
}
