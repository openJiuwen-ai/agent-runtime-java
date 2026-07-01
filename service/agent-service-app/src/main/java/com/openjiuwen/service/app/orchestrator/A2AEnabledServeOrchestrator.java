/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient.RemoteInputRequiredException;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A-aware orchestrator with interrupt-resume chain.
 *
 * <p>
 * Detects {@code "interrupt"} chunks from the agent handler, routes
 * {@code a2a_delegate} interrupts to a remote agent
 * via {@link A2ARemoteAgentClient}, and resumes the local agent with the remote
 * result. Other interrupts are forwarded
 * as {@code INPUT_REQUIRED}.
 *
 * @since 0.1.0
 */
public class A2AEnabledServeOrchestrator implements ServeOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(A2AEnabledServeOrchestrator.class);

    /**
     * No-op stream observer used as a sentinel in sync/query mode.
     */
    private static final QueryStreamObserver NOOP_OBSERVER = new QueryStreamObserver() {
        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /**
     * Prefix for orchestrator-owned shadow task ids, keeping them out of the real
     * A2A task id space.
     */
    private static final String SHADOW_KEY_PREFIX = "shadow:";

    private final AgentHandler agentHandler;
    private final TaskStore taskStore;
    private final A2ARemoteAgentClient a2aClient;
    private final A2ARemoteAgentCardRegistry registry;
    private final ActiveStreamRegistry streamRegistry;
    private final String agentId;

    /**
     * Constructs the orchestrator with required dependencies.
     *
     * @param agentHandler   the local agent handler
     * @param taskStore      the A2A task store for shadow tasks
     * @param a2aClient      the remote A2A agent client
     * @param registry       the remote agent card registry
     * @param streamRegistry the active stream registry for cancellation
     * @param agentId        this agent's identity for shadow task key namespacing
     */
    public A2AEnabledServeOrchestrator(AgentHandler agentHandler, TaskStore taskStore, A2ARemoteAgentClient a2aClient,
            A2ARemoteAgentCardRegistry registry, ActiveStreamRegistry streamRegistry, String agentId) {
        this.agentHandler = agentHandler;
        this.taskStore = taskStore;
        this.a2aClient = a2aClient;
        this.registry = registry;
        this.streamRegistry = streamRegistry;
        this.agentId = agentId == null || agentId.isBlank() ? "agent" : agentId;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Orchestrator query START conversationId={}", request.getConversationId());
        ServeRequest current = request;
        while (true) {
            Optional<ServeRequest> opt = syncResumePending(current);
            if (opt.isEmpty()) {
                return buildInterruptQueryResponse(request.getConversationId());
            }
            current = opt.get();

            QueryResponse response = agentHandler.query(current);
            Map<String, Object> interruptData = extractInterruptFromResponse(response);
            if (interruptData.isEmpty()) {
                return response;
            }

            Optional<ServeRequest> interruptResult = handleQueryInterrupt(interruptData, current, response);
            if (interruptResult.isEmpty()) {
                return response;
            }
            current = interruptResult.get();
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        log.info("Orchestrator streamQuery START conversationId={}", request.getConversationId());
        var handle = streamRegistry.register(request.getConversationId());
        try {
            ServeRequest current = request;
            while (!handle.isCancelled() && !observer.isCancelled()) {
                Optional<ServeRequest> opt = tryResumePending(current, observer, handle);
                if (opt.isEmpty()) {
                    return;
                }
                current = opt.get();

                QueryChunk interrupt = runAgentAndCaptureInterrupt(current, observer, handle);
                if (interrupt == null) {
                    return;
                }

                Optional<ServeRequest> interruptResult = handleInterrupt(interrupt, current, observer);
                if (interruptResult.isEmpty()) {
                    return;
                }
                current = interruptResult.get();
            }
        } finally {
            streamRegistry.unregister(request.getConversationId(), handle);
        }
    }

    /**
     * If a pending remote task exists, resume it.
     *
     * @param current  the current serve request
     * @param observer the query stream observer
     * @param handle   the stream cancellation handle
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> tryResumePending(ServeRequest current, QueryStreamObserver observer,
            StreamCancellationHandle handle) {
        List<Task> pending = findPending(current.getConversationId());
        if (pending.isEmpty()) {
            return Optional.of(current);
        }

        Task pt = pending.get(0);
        String agentName = metadataString(pt, "_agent_name");
        String remoteTaskId = metadataString(pt, "_remote_task_id");
        String streamMode = metadataString(pt, "_stream_mode");
        boolean isSse = InterruptData.STREAM_MODE_SSE.equals(streamMode);
        log.info("Orchestrator resuming pending task convId={} agent={} remoteTaskId={} streamMode={}",
                current.getConversationId(), agentName, remoteTaskId, streamMode);
        try {
            // Only pass the observer (stream the remote content to the client) when the
            // delegation opted into
            // SSE passthrough; otherwise resolve synchronously so the remote result reaches
            // the tool only.
            String content = isSse
                    ? a2aClient.callStreaming(agentName, current.lastUserQuery(), current.getConversationId(),
                            remoteTaskId, observer, current.getMetadata()).get()
                    : a2aClient.callSync(agentName, current.lastUserQuery(), current.getConversationId(),
                            remoteTaskId, current.getMetadata());
            deleteShadowTask(pt.id());
            return Optional.of(buildResumeRequest(current, content, "", ""));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                return refreshPendingOnRemoteInput(current, pt, rie, observer);
            }
            log.error("Remote call '{}' failed for pending task", agentName, e);
        } catch (RemoteInputRequiredException rie) {
            // Sync resume path: remote still needs input.
            return refreshPendingOnRemoteInput(current, pt, rie, observer);
        } catch (Exception e) {
            log.error("Remote call '{}' failed for pending task", agentName, e);
        }
        saveShadowTask(current.getConversationId(), agentName, metadataString(pt, "_remote_url"));
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Handles a remote INPUT_REQUIRED hit while resuming a pending task: refreshes the shadow task with the new
     * remote task id (so the next resume targets the right remote task) while preserving the stream mode, then
     * forwards the interrupt to the client. Agent name and stream mode are read from the existing task metadata.
     *
     * @param current  the current serve request
     * @param pt       the existing pending shadow task
     * @param rie      the remote input-required signal carrying the new remote task id
     * @param observer the query stream observer
     * @return {@link Optional#empty()} to stop the loop with the shadow task preserved
     */
    private Optional<ServeRequest> refreshPendingOnRemoteInput(ServeRequest current, Task pt,
            RemoteInputRequiredException rie, QueryStreamObserver observer) {
        saveShadowTask(current.getConversationId(), metadataString(pt, "_agent_name"),
                metadataString(pt, "_remote_url"), rie.getRemoteTaskId(), metadataString(pt, "_stream_mode"));
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", rie.getMessage())));
        return Optional.empty();
    }

    /**
     * Runs the agent and captures any interrupt chunk.
     *
     * @param current  the current serve request
     * @param observer the query stream observer
     * @param handle   the stream cancellation handle
     * @return the interrupt chunk, or {@code null} if the stream completed normally
     */
    private QueryChunk runAgentAndCaptureInterrupt(ServeRequest current, QueryStreamObserver observer,
            StreamCancellationHandle handle) {
        var interruptHolder = new java.util.concurrent.atomic.AtomicReference<QueryChunk>();
        agentHandler.streamQuery(current, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                    interruptHolder.set(chunk);
                    return;
                }
                observer.onNext(chunk);
            }

            @Override
            public void onComplete() {
                if (interruptHolder.get() == null) {
                    observer.onComplete();
                }
            }

            @Override
            public void onError(Throwable e) {
                log.error("Agent stream error", e);
                observer.onError(e);
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled() || observer.isCancelled();
            }
        });
        return interruptHolder.get();
    }

    /**
     * Routes an interrupt chunk.
     *
     * @param interrupt the interrupt chunk
     * @param current   the current serve request
     * @param observer  the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleInterrupt(QueryChunk interrupt, ServeRequest current,
            QueryStreamObserver observer) {
        var data = resolveInterruptData(interrupt);
        log.info("Orchestrator interrupt kind={} agentName={} toolName={} convId={}", data.kind(), data.agentName(),
                data.toolName(), current.getConversationId());
        if (InterruptData.KIND_A2A_DELEGATE.equals(data.kind())) {
            return handleA2ADelegate(data, current, observer);
        }
        log.info("Orchestrator forwarding ask_user interrupt to client convId={}", current.getConversationId());
        observer.onNext(interrupt);
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Delegates to remote agent: chooses SSE (streaming) or sync (blocking) path.
     *
     * @param data     the interrupt data
     * @param current  the current serve request
     * @param observer the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleA2ADelegate(InterruptData data, ServeRequest current,
            QueryStreamObserver observer) {
        if (InterruptData.STREAM_MODE_SSE.equals(data.streamMode())) {
            return delegateSse(data, current, observer);
        }
        return delegateSync(data, current, observer);
    }

    /**
     * SSE: streaming call — intermediate output forwards to observer.
     *
     * @param data     the interrupt data
     * @param current  the current serve request
     * @param observer the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> delegateSse(InterruptData data, ServeRequest current,
            QueryStreamObserver observer) {
        log.info("Orchestrator delegating (sse) to remote agent={}", data.agentName());
        try {
            String content = a2aClient.callStreaming(data.agentName(), data.message(), current.getConversationId(),
                    observer, current.getMetadata()).get();
            log.info("Orchestrator remote result received ({} chars), building resume", content.length());
            return Optional.of(buildResumeRequest(current, content, data.toolCallId(), data.toolName()));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                return handleRemoteInputRequired(data, current, observer, rie);
            }
            log.error("Remote call '{}' failed (sse)", data.agentName(), e);
        } catch (Exception e) {
            log.error("Remote call '{}' failed (sse)", data.agentName(), e);
        }
        saveShadowTask(current.getConversationId(), data.agentName(), registry.resolveUrl(data.agentName()));
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Sync: blocking call — only final result or interrupt returned.
     *
     * @param data     the interrupt data
     * @param current  the current serve request
     * @param observer the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> delegateSync(InterruptData data, ServeRequest current,
            QueryStreamObserver observer) {
        log.info("Orchestrator delegating (sync) to remote agent={}", data.agentName());
        try {
            String content = a2aClient.callSync(data.agentName(), data.message(), current.getConversationId(), null,
                    current.getMetadata());
            log.info("Orchestrator remote result received ({} chars), building resume", content.length());
            return Optional.of(buildResumeRequest(current, content, data.toolCallId(), data.toolName()));
        } catch (RemoteInputRequiredException rie) {
            return handleRemoteInputRequired(data, current, observer, rie);
        } catch (Exception e) {
            log.error("Remote call '{}' failed (sync)", data.agentName(), e);
        }
        saveShadowTask(current.getConversationId(), data.agentName(), registry.resolveUrl(data.agentName()));
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Handles remote INPUT_REQUIRED: saves shadow task, notifies client, and stops
     * the loop.
     *
     * @param data     the interrupt data
     * @param current  the current serve request
     * @param observer the query stream observer
     * @param rie      the remote input required exception
     * @return {@link Optional#empty()} always, indicating the loop should stop
     */
    private Optional<ServeRequest> handleRemoteInputRequired(InterruptData data, ServeRequest current,
            QueryStreamObserver observer, RemoteInputRequiredException rie) {
        log.info("Orchestrator remote INPUT_REQUIRED convId={} remoteTaskId={}", current.getConversationId(),
                rie.getRemoteTaskId());
        saveShadowTask(current.getConversationId(), data.agentName(), registry.resolveUrl(data.agentName()),
                rie.getRemoteTaskId(), data.streamMode());
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", rie.getMessage())));
        observer.onComplete();
        return Optional.empty();
    }

    @Override
    public void cancelActive(String conversationId) {
        streamRegistry.cancel(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        cancelActive(conversationId);
        agentHandler.clearSession(conversationId);
        var result = taskStore.list(ListTasksParams.builder().contextId(conversationId).build());
        for (Task t : result.tasks()) {
            deleteShadowTask(t.id());
        }
        log.info("Reset {}: {} A2A tasks cleaned", conversationId, result.tasks().size());
    }

    /**
     * Structured interrupt data decoded from a {@code QueryChunk("interrupt")}.
     */
    private record InterruptData(String kind, String agentName, String message, String toolCallId, String toolName,
            String streamMode) {
        static final String KIND_ASK_USER = "ask_user";
        static final String KIND_A2A_DELEGATE = "a2a_delegate";
        static final InterruptData EMPTY = new InterruptData(KIND_ASK_USER, "", "", "", "", "");
        static final String STREAM_MODE_SSE = "sse";
    }

    /**
     * Sync variant of {@link #tryResumePending} for non-streaming query mode.
     *
     * @param current the current serve request
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> syncResumePending(ServeRequest current) {
        List<Task> pending = findPending(current.getConversationId());
        if (pending.isEmpty()) {
            return Optional.of(current);
        }

        Task pt = pending.get(0);
        String agentName = metadataString(pt, "_agent_name");
        String remoteTaskId = metadataString(pt, "_remote_task_id");
        log.info("Orchestrator syncResumePending convId={} agent={} remoteTaskId={}", current.getConversationId(),
                agentName, remoteTaskId);
        try {
            String content = a2aClient.callSync(agentName, current.lastUserQuery(), current.getConversationId(),
                    remoteTaskId, current.getMetadata());
            deleteShadowTask(pt.id());
            return Optional.of(buildResumeRequest(current, content, "", ""));
        } catch (RemoteInputRequiredException e) {
            return Optional.empty(); // INPUT_REQUIRED still pending
        } catch (Exception e) {
            log.error("Remote call '{}' failed for pending task", agentName, e);
        }
        saveShadowTask(current.getConversationId(), agentName, metadataString(pt, "_remote_url"));
        return Optional.empty();
    }

    /**
     * Handles a2a_delegate interrupt in query mode.
     *
     * @param interruptData the interrupt data map
     * @param current       the current serve request
     * @param response      the query response
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleQueryInterrupt(Map<String, Object> interruptData, ServeRequest current,
            QueryResponse response) {
        var data = resolveInterruptDataFromMap(interruptData);
        log.info("Orchestrator query interrupt kind={} agentName={} convId={}", data.kind(), data.agentName(),
                current.getConversationId());
        if (InterruptData.KIND_A2A_DELEGATE.equals(data.kind())) {
            log.info("Orchestrator query delegating (sync) to remote agent={}", data.agentName());
            try {
                String content = a2aClient.callSync(data.agentName(), data.message(), current.getConversationId(), null,
                        current.getMetadata());
                log.info("Orchestrator query remote result received ({} chars), building resume", content.length());
                return Optional.of(buildResumeRequest(current, content, data.toolCallId(), data.toolName()));
            } catch (RemoteInputRequiredException rie) {
                // Use remote agent's interrupt message instead of Agent A's internal one
                interruptData.put("message", rie.getMessage());
                saveShadowTask(current.getConversationId(), data.agentName(), registry.resolveUrl(data.agentName()),
                        rie.getRemoteTaskId(), data.streamMode());
            } catch (Exception e) {
                log.error("Remote call '{}' failed", data.agentName(), e);
                saveShadowTask(current.getConversationId(), data.agentName(), registry.resolveUrl(data.agentName()));
            }
        }
        return Optional.empty(); // non-a2a_delegate or error → stop loop, return interrupt to caller
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInterruptFromResponse(QueryResponse response) {
        if (response.getResult() instanceof Map<?, ?> m && m.get("_interrupt") instanceof Map<?, ?> interrupt) {
            return (Map<String, Object>) interrupt;
        }
        return new LinkedHashMap<>();
    }

    private static QueryResponse buildInterruptQueryResponse(String convId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("_interrupt", Map.of("message", "Remote agent requires input"));
        return new QueryResponse(result, convId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static InterruptData resolveInterruptDataFromMap(Map<String, Object> data) {
        Object contextObj = data.get("context");
        Map context = contextObj instanceof Map ? (Map) contextObj : null;
        String kind = context != null && context.get("_interrupt_kind") instanceof String s
                ? s
                : data.get("agentName") instanceof String
                        ? InterruptData.KIND_A2A_DELEGATE
                        : InterruptData.KIND_ASK_USER;
        String agentName = context != null && context.get("agentName") instanceof String an
                ? an
                : data.get("agentName") instanceof String an2 ? an2 : "";
        String message = data.get("message") instanceof String s ? s : "";
        String toolCallId = data.get("toolCallId") instanceof String s ? s : "";
        String toolName = data.get("toolName") instanceof String s ? s : "";
        String streamMode = context != null && context.get("_stream_mode") instanceof String s
                ? s
                : data.get("_stream_mode") instanceof String s2 ? s2 : "";
        return new InterruptData(kind, agentName, message, toolCallId, toolName, streamMode);
    }

    private List<Task> findPending(String conversationId) {
        // Use get() instead of list() — list() goes through transformTask()
        // which rebuilds the Task and may drop metadata in some code paths.
        Task task = taskStore.get(shadowTaskId(conversationId));
        if (task != null && task.status() != null && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            return List.of(task);
        }
        return List.of();
    }

    /**
     * Builds this agent's shadow task id for a conversation. The id is namespaced
     * by agent identity so that, when
     * several agents share one task store (e.g. the same Redis) and the
     * conversation id is passed through unchanged,
     * each agent's shadow task occupies a distinct key instead of overwriting the
     * others.
     *
     * @param conversationId the passed-through conversation id
     * @return the namespaced shadow task id
     */
    private String shadowTaskId(String conversationId) {
        return SHADOW_KEY_PREFIX + agentId + ":" + conversationId;
    }

    private void deleteShadowTask(String taskId) {
        taskStore.delete(taskId);
    }

    private void saveShadowTask(String convId, String agentName, String url) {
        saveShadowTask(convId, agentName, url, "", "");
    }

    private void saveShadowTask(String convId, String agentName, String url, String remoteTaskId, String streamMode) {
        log.info("Orchestrator saveShadowTask convId={} agent={} remoteTaskId={} streamMode={}", convId, agentName,
                remoteTaskId, streamMode);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (url != null) {
            meta.put("_remote_url", url);
        }
        if (agentName != null) {
            meta.put("_agent_name", agentName);
        }
        if (remoteTaskId != null && !remoteTaskId.isBlank()) {
            meta.put("_remote_task_id", remoteTaskId);
        }
        if (streamMode != null && !streamMode.isBlank()) {
            meta.put("_stream_mode", streamMode);
        }
        taskStore.save(Task.builder().id(shadowTaskId(convId)).contextId(convId)
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(meta.isEmpty() ? null : meta).build(), true);
    }

    private ServeRequest buildResumeRequest(ServeRequest original, String toolContent, String toolCallId,
            String toolName) {
        log.info("Orchestrator buildResumeRequest convId={} toolName={} toolCallId={} toolContentLen={}",
                original.getConversationId(), toolName, toolCallId, toolContent != null ? toolContent.length() : 0);
        // AgentCore resumes from its persisted session checkpoint and reads only the
        // query
        // (lastUserQuery() → INPUT_QUERY → normalizeResumeInput → InteractiveInput);
        // the ReAct
        // invoke path ignores INPUT_MESSAGES. Carrying the original history here was
        // therefore
        // dead weight that also forced overwriting the original user question with the
        // remote
        // result. Send a single user message holding the remote result so the resume
        // query is
        // unambiguous and no stale user/tool/interrupt history leaks back into the
        // prompt.
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", toolContent);
        messages.add(userMsg);
        ServeRequest resumeReq = new ServeRequest();
        resumeReq.setConversationId(original.getConversationId());
        resumeReq.setStream(true);
        resumeReq.setMessages(messages);
        resumeReq.setUserId(original.getUserId());
        resumeReq.setSpaceId(original.getSpaceId());
        resumeReq.setTenantId(original.getTenantId());
        resumeReq.setMetadata(original.getMetadata());
        return resumeReq;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static InterruptData resolveInterruptData(QueryChunk chunk) {
        if (!QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
            return InterruptData.EMPTY;
        }
        Object rawObj = chunk.getData();
        if (!(rawObj instanceof Map)) {
            return InterruptData.EMPTY;
        }
        var raw = (Map) rawObj;
        Object contextObj = raw.get("context");
        Map context = contextObj instanceof Map ? (Map) contextObj : null;
        String kind = context != null && context.get("_interrupt_kind") instanceof String s
                ? s
                : raw.get("agentName") instanceof String
                        ? InterruptData.KIND_A2A_DELEGATE
                        : InterruptData.KIND_ASK_USER;
        String agentName = context != null && context.get("agentName") instanceof String an
                ? an
                : raw.get("agentName") instanceof String an2 ? an2 : "";
        String message = raw.get("message") instanceof String s ? s : "";
        String toolCallId = raw.get("toolCallId") instanceof String s ? s : "";
        String toolName = raw.get("toolName") instanceof String s ? s : "";
        String streamMode = context != null && context.get("_stream_mode") instanceof String s
                ? s
                : raw.get("_stream_mode") instanceof String s2 ? s2 : "";
        return new InterruptData(kind, agentName, message, toolCallId, toolName, streamMode);
    }

    /**
     * Safely extracts a string from task metadata.
     *
     * @param task the task
     * @param key  the metadata key
     * @return the metadata value as string, or empty string if not present
     */
    private static String metadataString(Task task, String key) {
        Object value = task.metadata().get(key);
        return value instanceof String s ? s : "";
    }
}
