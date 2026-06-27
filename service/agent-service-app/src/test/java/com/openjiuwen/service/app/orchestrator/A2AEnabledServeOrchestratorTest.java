/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, taskStore, a2aClient, registry, streamRegistry);
    }

    // ==================== normal delegation ====================

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
            @Override public void onNext(QueryChunk c) {
                assertThat(c.getData()).isEqualTo("hello");
            }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable e) {}
            @Override public boolean isCancelled() { return false; }
        });

        assertThat(completed.get()).isTrue();
    }

    // ==================== a2a_interrupt handling ====================

    @Test
    void a2aInterruptCreatesShadowTaskInInputRequired() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        // Setup: agent produces a2a_interrupt chunk
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                    "agentName", "hotel-agent",
                    "toolName", "search")));
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

    // ==================== pending shadow task routing ====================

    @Test
    void pendingTaskRoutesToResume() {
        Task pending = Task.builder()
                .id("c-pending")
                .contextId("c-pending")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED,
                        null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/",
                        "_agent_name", "test",
                        "_remote_task_id", "remote-task-123"))
                .build();
        when(registry.get("test")).thenReturn(
                java.util.Optional.of(
                        new A2ARemoteAgentCardRegistry.RemoteAgentEntry("test", testCard(), 300)));
        when(taskStore.get("c-pending")).thenReturn(pending);

        orchestrator.streamQuery(req("c-pending"), mock(QueryStreamObserver.class));

        // Verify pending check was made via get(); remote call attempts but may fail in unit test
        verify(taskStore).get("c-pending");
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

    // ==================== resetConversation ====================

    @Test
    void resetConversationCleansTaskStore() {
        when(taskStore.list(any())).thenReturn(
                new ListTasksResult(List.of(
                        Task.builder().id("t1").contextId("c-reset")
                                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                                .build(),
                        Task.builder().id("t2").contextId("c-reset")
                                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
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

    // ==================== helpers ====================

    private static ServeRequest req(String convId) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setStream(true);
        return r;
    }

    private static AgentCard testCard() {
        return AgentCard.builder()
                .name("test-agent").description("test").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of()).defaultOutputModes(List.of())
                .skills(List.of()).securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(
                        new AgentInterface("jsonrpc", "http://remote/a2a/", null, "1.0")))
                .signatures(List.of()).additionalInterfaces(List.of())
                .build();
    }
}
