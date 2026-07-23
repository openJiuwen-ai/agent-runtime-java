/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class DefaultRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(DefaultRemoteAgentCaller.class);

    private final A2ARemoteAgentCardRegistry registry;
    private final Map<String, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    public DefaultRemoteAgentCaller(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    /**
     * Invokes the remote agent via the A2A SDK streaming client.
     *
     * <p>Unlike the legacy {@code A2ARemoteAgentClient} which exposed separate
     * {@code callStreaming} / {@code callSync} methods with streaming and
     * non-streaming SDK clients respectively, this unified entry always uses a
     * streaming client and lets the caller decide whether to pass a passthrough
     * observer. In sync mode (null/NOOP passthrough) intermediate
     * {@link TaskArtifactUpdateEvent}s are still processed but forwarded to the
     * no-op observer; the captured answer text is what callers consume. This
     * unification keeps a single SDK event-handling path but is a deliberate
     * divergence from the legacy "non-streaming client for sync" behaviour.
     *
     * <p>Terminal signalling contract:
     * <ul>
     *   <li>normal completion → {@code observer.onComplete()}</li>
     *   <li>INPUT_REQUIRED → {@code observer.onNext(TYPE_INTERRUPT)} then
     *       {@code observer.onComplete()}</li>
     *   <li>timeout / execution failure → {@code observer.onError(...)} (no
     *       subsequent {@code onComplete})</li>
     * </ul>
     */
    @Override
    public void call(RemoteAgentCall call, QueryStreamObserver observer) {
        A2ARemoteAgentCardRegistry.RemoteAgentEntry entry;
        try {
            entry = registry.get(call.agentId()).orElseThrow(
                    () -> new IllegalStateException("Unknown remote agent: " + call.agentId()));
        } catch (RuntimeException ex) {
            observer.onError(ex);
            return;
        }
        String contextId = call.contextId() != null
                ? call.contextId()
                : java.util.UUID.randomUUID().toString();
        String message = call.message() != null && !call.message().isBlank()
                ? call.message() : call.serveRequest().lastUserQuery();
        Message.Builder msgBuilder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(contextId)
                .parts(List.<Part<?>>of(new TextPart(message)));
        if (call.taskId() != null && !call.taskId().isBlank()) {
            msgBuilder.taskId(call.taskId());
        }
        Message msg = msgBuilder.build();
        log.info("DefaultRemoteAgentCaller.call agent={} taskId={} contextId={} textLen={}",
                call.agentId(), call.taskId() != null ? call.taskId() : "new",
                contextId, message != null ? message.length() : 0);

        Client client = createClient(entry.card(), true);
        var params = MessageSendParams.builder()
                .message(msg)
                .metadata(call.serveRequest().getMetadata())
                .build();
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
                if (event instanceof TaskUpdateEvent tue) {
                    if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                        handleArtifact(aue, result, observer);
                    } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                        handleStatusUpdate(sue, result);
                    }
                } else if (event instanceof TaskEvent te) {
                    handleTaskEvent(te, result);
                }
            }), result::completeExceptionally, null);
        } catch (RuntimeException ex) {
            observer.onError(new RemoteAgentException(
                    "Remote agent '" + call.agentId() + "' failed", ex));
            return;
        }

        try {
            result.get(entry.timeoutSeconds(), TimeUnit.SECONDS);
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (TimeoutException e) {
            observer.onError(new RemoteAgentException(
                    "Remote agent '" + call.agentId() + "' timed out after " + entry.timeoutSeconds() + "s", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observer.onError(new RemoteAgentException(
                    "Interrupted while waiting for remote agent '" + call.agentId() + "'", e));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                        Map.of("message", rie.getMessage(), "remote_task_id", rie.getRemoteTaskId())));
                observer.onComplete();
            } else {
                observer.onError(new RemoteAgentException(
                        "Remote agent '" + call.agentId() + "' failed", e.getCause()));
            }
        }
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }

    private Client createClient(AgentCard card, boolean isStreaming) {
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(
                card.name() + ":" + isStreaming,
                k -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                        .build()));
    }

    private static <T> T withApplicationClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader appCl = DefaultRemoteAgentCaller.class.getClassLoader();
        if (appCl == null || original == appCl) {
            return action.get();
        }
        try {
            thread.setContextClassLoader(appCl);
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<String> result,
            QueryStreamObserver observer) {
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) {
            return;
        }
        String raw = extractText(a.parts());
        if (raw.isEmpty()) {
            return;
        }
        // Forward every artifact to the observer, even after the answer future
        // is complete — late-arriving chunks are legitimate in SSE passthrough
        // and must reach the client stream. Only the future completion is
        // guarded (matches the original A2ARemoteAgentClient behaviour).
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, raw));
        RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(answer -> {
            if (!result.isDone()) {
                result.complete(answer);
            }
        });
    }

    private void handleStatusUpdate(TaskStatusUpdateEvent sue, CompletableFuture<String> result) {
        if (sue.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = sue.status().message() != null
                    ? extractText(sue.status().message().parts()) : "";
            if (!result.isDone()) {
                result.completeExceptionally(new RemoteInputRequiredException(
                        statusText.isBlank() ? "Remote agent requires input" : statusText,
                        sue.taskId() != null ? sue.taskId() : ""));
            }
        } else if (sue.status().state().isFinal() && !result.isDone()) {
            result.complete("");
        }
    }

    private void handleTaskEvent(TaskEvent te, CompletableFuture<String> result) {
        if (result.isDone()) {
            return;
        }
        Task task = te.getTask();
        if (task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = task.status().message() != null
                    ? extractText(task.status().message().parts()) : "";
            result.completeExceptionally(new RemoteInputRequiredException(
                    statusText.isBlank() ? "Remote agent requires input" : statusText,
                    task.id() != null ? task.id() : ""));
        } else if (task.status().state().isFinal()) {
            String text = task.artifacts() != null && !task.artifacts().isEmpty()
                    ? extractText(task.artifacts().get(0).parts()) : "";
            result.complete(text);
        }
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }
}
