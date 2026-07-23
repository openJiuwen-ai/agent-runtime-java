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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Baseline {@link RemoteAgentCaller} using the official A2A SDK
 * {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * <p>This is the SPI successor of the legacy {@code A2ARemoteAgentClient} class
 * (same filename, same class name) — it now implements {@link RemoteAgentCaller}
 * and exposes a single {@link #call(RemoteAgentCall, QueryStreamObserver)} entry
 * point. The legacy separate {@code callStreaming} / {@code callSync} methods
 * are unified: callers pass a passthrough observer for streaming mode and a
 * NOOP observer for sync mode.
 *
 * <p>Structured failure codes ({@link RemoteAgentException#CODE_REMOTE_TIMEOUT},
 * {@link RemoteAgentException#CODE_REMOTE_STREAM_CLOSED},
 * {@link RemoteAgentException#CODE_REMOTE_ERROR}) let the orchestrator decide
 * whether a remote failure is recoverable (resume the parent agent with an
 * error tool result) or terminal.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<String, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructs the remote agent client.
     *
     * @param registry the remote agent card registry
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    /**
     * Invokes the remote agent via the A2A SDK streaming client.
     *
     * <p>Unlike the legacy separate {@code callStreaming} / {@code callSync}
     * methods with streaming and non-streaming SDK clients respectively, this
     * unified entry always uses a streaming client and lets the caller decide
     * whether to pass a passthrough observer. In sync mode (null/NOOP
     * passthrough) intermediate {@link TaskArtifactUpdateEvent}s are still
     * processed but forwarded to the no-op observer; the captured answer text
     * is what callers consume. This unification keeps a single SDK
     * event-handling path but is a deliberate divergence from the legacy
     * "non-streaming client for sync" behaviour.
     *
     * <p>Terminal signalling contract:
     * <ul>
     *   <li>normal completion → {@code observer.onComplete()}</li>
     *   <li>INPUT_REQUIRED → {@code observer.onNext(TYPE_INTERRUPT)} then
     *       {@code observer.onComplete()}</li>
     *   <li>timeout / execution failure / premature stream close →
     *       {@code observer.onError(...)} with a {@link RemoteAgentException}
     *       whose {@link RemoteAgentException#getCode() code} identifies the
     *       failure category (no subsequent {@code onComplete})</li>
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
        MessageSendParams params = buildSendParams(call, message, contextId);
        log.info("A2ARemoteAgentClient.call agent={} taskId={} contextId={} textLen={}",
                call.agentId(), call.taskId() != null ? call.taskId() : "new",
                contextId, message != null ? message.length() : 0);

        Client client = createClient(entry.card(), true);
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
            }), error -> completeOnStreamEnd(call.agentId(), result, error), null);
        } catch (RuntimeException ex) {
            observer.onError(new RemoteAgentException(
                    "Remote agent '" + call.agentId() + "' failed", ex));
            return;
        }

        try {
            applyTimeout(result, call.agentId(), entry.timeoutSeconds()).get();
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteInputRequiredException rie) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                        Map.of("message", rie.getMessage(), "remote_task_id", rie.getRemoteTaskId())));
                if (!observer.isCancelled()) {
                    observer.onComplete();
                }
            } else if (cause instanceof RemoteAgentException rae) {
                observer.onError(rae);
            } else {
                observer.onError(new RemoteAgentException(
                        "Remote agent '" + call.agentId() + "' failed", cause));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observer.onError(new RemoteAgentException(RemoteAgentException.CODE_REMOTE_ERROR,
                    "Interrupted while waiting for remote agent '" + call.agentId() + "'", e));
        }
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }

    /**
     * Builds the SDK send parameters from a {@link RemoteAgentCall}, preserving
     * both protocol metadata levels: params-level metadata from
     * {@code serveRequest.getMetadata()} and message-level metadata from
     * {@code serveRequest.lastUserMessageMetadata()}.
     *
     * @param call      the remote call coordinates
     * @param message   the resolved message text
     * @param contextId the resolved context id
     * @return the SDK message send parameters
     */
    static MessageSendParams buildSendParams(RemoteAgentCall call, String message, String contextId) {
        Map<String, Object> paramsMetadata = immutableMetadata(call.serveRequest().getMetadata());
        Map<String, Object> messageMetadata = immutableMetadata(call.serveRequest().lastUserMessageMetadata());
        Message.Builder messageBuilder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(contextId)
                .parts(List.<Part<?>>of(new TextPart(message)))
                .metadata(messageMetadata);
        if (call.taskId() != null && !call.taskId().isBlank()) {
            messageBuilder.taskId(call.taskId());
        }
        return MessageSendParams.builder()
                .message(messageBuilder.build())
                .metadata(paramsMetadata)
                .build();
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Applies the SDK streaming callback contract. A {@code null} error denotes
     * normal transport EOF, not an exceptional callback argument — when the
     * result future is not yet completed, EOF is classified as
     * {@link RemoteAgentException#CODE_REMOTE_STREAM_CLOSED} so callers can
     * recover instead of hanging on a non-terminal stream close.
     *
     * @param agentName remote agent name
     * @param result    call result future
     * @param error     transport error, or {@code null} for EOF
     * @return whether this callback completed the result exceptionally
     */
    static boolean completeOnStreamEnd(String agentName, CompletableFuture<String> result, Throwable error) {
        if (result.isDone()) {
            log.debug("A2A stream closed after terminal event agent={}", agentName);
            return false;
        }
        RemoteAgentException failure;
        if (error == null) {
            failure = new RemoteAgentException(RemoteAgentException.CODE_REMOTE_STREAM_CLOSED,
                    "Remote agent '" + agentName + "' closed the stream before a terminal event", null);
        } else {
            failure = new RemoteAgentException(RemoteAgentException.CODE_REMOTE_ERROR,
                    "Remote agent '" + agentName + "' stream failed", error);
        }
        if (!result.completeExceptionally(failure)) {
            log.debug("A2A stream closed after terminal event agent={}", agentName);
            return false;
        }
        if (error == null) {
            log.warn("A2A stream closed before terminal event agent={}", agentName);
        } else {
            log.warn("A2A stream failed agent={}: {}", agentName, error.getMessage());
        }
        return true;
    }

    /**
     * Applies the configured timeout to the result future and translates a
     * {@link TimeoutException} into a {@link RemoteAgentException} carrying
     * {@link RemoteAgentException#CODE_REMOTE_TIMEOUT}.
     *
     * @param result         the call result future
     * @param agentName      remote agent name
     * @param timeoutSeconds configured timeout
     * @return the timeout-guarded future
     */
    static CompletableFuture<String> applyTimeout(CompletableFuture<String> result, String agentName,
            int timeoutSeconds) {
        return result.orTimeout(timeoutSeconds, TimeUnit.SECONDS).handle((answer, error) -> {
            if (error == null) {
                return answer;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            if (cause instanceof TimeoutException) {
                log.warn("A2A stream timed out agent={} timeoutSeconds={}", agentName, timeoutSeconds);
                throw new RemoteAgentException(RemoteAgentException.CODE_REMOTE_TIMEOUT,
                        "Remote agent '" + agentName + "' timed out after " + timeoutSeconds + "s", cause);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CompletionException(cause);
        });
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
        ClassLoader appCl = A2ARemoteAgentClient.class.getClassLoader();
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
