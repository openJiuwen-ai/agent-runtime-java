/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the A2A-enabled serve orchestrator.
 */
class A2AEnabledServeOrchestratorTest {
    private AgentHandler agentHandler;
    private TaskStore taskStore;
    private A2ARemoteAgentCardRegistry registry;
    private ActiveStreamRegistry streamRegistry;
    private A2ARemoteAgentClient a2aClient;
    private A2AEnabledServeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        taskStore = mock(TaskStore.class);
        a2aClient = mock(A2ARemoteAgentClient.class);
        registry = mock(A2ARemoteAgentCardRegistry.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        when(streamRegistry.register(anyString())).thenReturn(mock(StreamCancellationHandle.class));
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, registry, streamRegistry,
                "test-agent");
    }

    @Test
    void normalChunksPassThroughToObserver() {
        when(taskStore.get(anyString())).thenReturn(null);
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk("chunk", "hello"));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        AtomicBoolean completed = new AtomicBoolean(false);
        orchestrator.streamQuery(req("c1"), new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk c) {
                assertThat(c.getData()).isEqualTo("hello");
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });

        assertThat(completed.get()).isTrue();
    }

    @Test
    void a2aInterruptCreatesShadowTaskInInputRequired() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        // Setup: agent produces a2a_interrupt chunk
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    Map.of("agentName", "hotel-agent", "toolName", "search")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        var card = testCard();
        when(registry.get("hotel-agent")).thenReturn(
                java.util.Optional.of(new A2ARemoteAgentCardRegistry.RemoteAgentEntry("hotel-agent", card, 300)));
        when(registry.resolveUrl(anyString())).thenReturn("http://remote/a2a/");

        orchestrator.streamQuery(req("c-int"), mock(QueryStreamObserver.class));

        // Verify shadow task was saved with INPUT_REQUIRED
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task saved = taskCaptor.getValue();
        assertThat(saved.contextId()).isEqualTo("c-int");
        assertThat(saved.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    }

    @Test
    void interruptWithoutA2aInterruptChunkDoesNotCreateShadowTask() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk("chunk", "normal data"));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-normal"), mock(QueryStreamObserver.class));

        verify(taskStore, never()).save(any(), anyBoolean());
    }

    @Test
    void pendingTaskRoutesToResume() {
        // Shadow tasks are namespaced by agent identity (see
        // A2AEnabledServeOrchestrator#shadowTaskId).
        String shadowId = "shadow:test-agent:c-pending";
        Task pending = Task.builder().id(shadowId).contextId("c-pending")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id",
                        "remote-task-123"))
                .build();
        when(registry.get("test")).thenReturn(
                java.util.Optional.of(new A2ARemoteAgentCardRegistry.RemoteAgentEntry("test", testCard(), 300)));
        when(taskStore.get(shadowId)).thenReturn(pending);

        orchestrator.streamQuery(req("c-pending"), mock(QueryStreamObserver.class));

        // Verify pending check was made via get() on the namespaced shadow key; remote
        // call
        // attempts but may fail in unit test
        verify(taskStore).get(shadowId);
    }

    @Test
    void pendingResumeWithoutSseModeUsesSyncCallNoPassthrough() {
        String shadowId = "shadow:test-agent:c-sync";
        Task pending = Task.builder().id(shadowId).contextId("c-sync")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1"))
                .build();
        // Deleted after a successful resume, so the second findPending sees nothing.
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callSync(anyString(), any(), anyString(), any(), any())).thenReturn("42");

        orchestrator.streamQuery(req("c-sync"), mock(QueryStreamObserver.class));

        // No _stream_mode → resolve synchronously; the client observer is never handed
        // to the remote call.
        verify(a2aClient).callSync(eq("test"), any(), eq("c-sync"), any(), any());
        verify(a2aClient, never()).callStreaming(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void pendingResumeWithSseModeStreamsThroughObserver() {
        String shadowId = "shadow:test-agent:c-sse";
        Task pending = Task.builder().id(shadowId).contextId("c-sse")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callStreaming(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("42"));

        orchestrator.streamQuery(req("c-sse"), mock(QueryStreamObserver.class));

        // _stream_mode=sse → stream the remote content to the client observer.
        verify(a2aClient).callStreaming(eq("test"), any(), eq("c-sse"), any(), any(), any());
        verify(a2aClient, never()).callSync(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void resumeInputRequiredRefreshesRemoteTaskIdKeepsStreamMode() throws Exception {
        String shadowId = "shadow:test-agent:c-multi";
        Task pending = Task.builder().id(shadowId).contextId("c-multi")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id",
                        "rt-old", "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        // Remote still needs input on resume, carrying a fresh remote task id.
        var rie = new A2ARemoteAgentClient.RemoteInputRequiredException("need more", "rt-new");
        when(a2aClient.callStreaming(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(rie));

        orchestrator.streamQuery(req("c-multi"), mock(QueryStreamObserver.class));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task resaved = taskCaptor.getValue();
        assertThat(resaved.metadata()).containsEntry("_remote_task_id", "rt-new");
        assertThat(resaved.metadata()).containsEntry("_stream_mode", "sse");
    }

    @Test
    void noPendingTaskDelegatesToAgent() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-clean"), mock(QueryStreamObserver.class));

        verify(agentHandler).streamQuery(any(), any());
    }

    @Test
    void resetConversationCleansTaskStore() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of(
                Task.builder().id("t1").contextId("c-reset").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .build(),
                Task.builder().id("t2").contextId("c-reset").status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                        .build())));

        orchestrator.resetConversation("c-reset");

        verify(agentHandler).clearSession("c-reset");
        verify(taskStore).delete("t1");
        verify(taskStore).delete("t2");
    }

    @Test
    void resetWithNoTasksCleansOnlySession() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        orchestrator.resetConversation("c-empty");

        verify(agentHandler).clearSession("c-empty");
        verify(taskStore, never()).delete(any());
    }

    private static ServeRequest req(String convId) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setStream(true);
        return r;
    }

    private static AgentCard testCard() {
        return AgentCard.builder().name("test-agent").description("test").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of())
                .defaultOutputModes(List.of()).skills(List.of()).securitySchemes(Map.of())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("jsonrpc", "http://remote/a2a/", null, "1.0")))
                .signatures(List.of()).additionalInterfaces(List.of()).build();
    }
}
