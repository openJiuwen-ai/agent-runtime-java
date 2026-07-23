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
 * are unified: callers select mode via {@link RemoteAgentCall#streaming()}.
 *
 * <p>To preserve the original behavior, the implementation branches on
 * {@link RemoteAgentCall#streaming()}:
 * <ul>
 *   <li><b>Streaming mode</b> ({@code streaming=true}): uses a streaming SDK
 *       client ({@code createClient(card, true)}), forwards every
 *       {@link TaskArtifactUpdateEvent} chunk to the observer, and routes
 *       INPUT_REQUIRED / failures / timeouts through
 *       {@code observer.onNext(TYPE_INTERRUPT)} / {@code observer.onError(...)}.
 *       Mirrors the legacy {@code callStreaming} contract.</li>
 *   <li><b>Sync mode</b> ({@code streaming=false}): uses a non-streaming SDK
 *       client ({@code createClient(card, false)}), only consumes the terminal
 *       {@link TaskEvent}, and throws {@link RemoteInputRequiredException} /
 *       {@link RemoteAgentException} to the caller. The captured answer text is
 *       emitted as a final {@code QueryChunk("chunk", answer)} + {@code onComplete()}
 *       so the orchestrator's capturing observer can extract it. Mirrors the
 *       legacy {@code callSync} contract.</li>
 * </ul>
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
     * Invokes the remote agent, branching on {@link RemoteAgentCall#streaming()}
     * to preserve the legacy {@code callStreaming} / {@code callSync} split.
     *
     * <p>Terminal signalling contract:
     * <ul>
     *   <li>Streaming mode:
     *     <ul>
     *       <li>normal completion → {@code observer.onComplete()}</li>
     *       <li>INPUT_REQUIRED → {@code observer.onNext(TYPE_INTERRUPT)} then
     *           {@code observer.onComplete()}</li>
     *       <li>timeout / execution failure / premature stream close →
     *           {@code observer.onError(...)} with a {@link RemoteAgentException}
     *           (no subsequent {@code onComplete})</li>
     *     </ul>
     *   </li>
     *   <li>Sync mode:
     *     <ul>
     *       <li>normal completion → {@code observer.onNext(TYPE_CHUNK, answer)}
     *           then {@code observer.onComplete()}</li>
     *       <li>INPUT_REQUIRED → throws {@link RemoteInputRequiredException}
     *           (no observer notification)</li>
     *       <li>timeout / execution failure → throws {@link RemoteAgentException}
     *           (no observer notification)</li>
     *     </ul>
     *   </li>
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
        String message = call.message();
        MessageSendParams params = buildSendParams(call, message, contextId);
        log.info("A2ARemoteAgentClient.call agent={} taskId={} contextId={} textLen={} streaming={}",
                call.agentId(), call.taskId() != null ? call.taskId() : "new",
                contextId, message != null ? message.length() : 0, call.streaming());

        if (call.streaming()) {
            callStreaming(call, entry, params, observer);
        } else {
            callSync(call, entry, params, observer);
        }
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }

    /**
     * Streaming path: uses a streaming SDK client, forwards every artifact chunk
     * to the observer, and routes terminal signals through the observer.
     *
     * <p>This preserves the legacy {@code callStreaming} contract: the answer is
     * tapped from the {@code type=answer} envelope (not consumed from the
     * stream), and premature stream close fails the call with
     * {@link RemoteAgentException#CODE_REMOTE_STREAM_CLOSED}.
     */
    private void callStreaming(RemoteAgentCall call, A2ARemoteAgentCardRegistry.RemoteAgentEntry entry,
            MessageSendParams params, QueryStreamObserver observer) {
        Client client = createClient(entry.card(), true);
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
            if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                    handleArtifact(aue, result, observer);
                } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                    handleStatusUpdate(sue, result);
                } else {
                    log.debug("Unknown update event type: {}", tue.getUpdateEvent().getClass().getSimpleName());
                }
            } else if (event instanceof TaskEvent te) {
                handleTaskEvent(te, result);
            } else {
                log.debug("Unknown event type: {}", event.getClass().getSimpleName());
            }
        }), error -> completeOnStreamEnd(call.agentId(), result, error), null);

        try {
            applyTimeout(result, call.agentId(), entry.timeoutSeconds()).get();
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteInputRequiredException rie) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                        Map.of("message", rie.getMessage(), "remote_task_id",
                                rie.getRemoteTaskId() != null ? rie.getRemoteTaskId() : "")));
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

    /**
     * Sync path: uses a non-streaming SDK client, only consumes the terminal
     * {@link TaskEvent}, and throws {@link RemoteInputRequiredException} /
     * {@link RemoteAgentException} to the caller.
     *
     * <p>This preserves the legacy {@code callSync} contract: the answer is the
     * raw text from {@code task.artifacts()}, and failures propagate as thrown
     * exceptions (not via observer). The captured answer is emitted as a final
     * {@code QueryChunk("chunk", answer)} + {@code onComplete()} so the
     * orchestrator's capturing observer can extract it before the SPI returns.
     */
    private void callSync(RemoteAgentCall call, A2ARemoteAgentCardRegistry.RemoteAgentEntry entry,
            MessageSendParams params, QueryStreamObserver observer) {
        Client client = createClient(entry.card(), false);
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
            if (event instanceof TaskEvent te) {
                handleTaskEvent(te, result);
            } else {
                log.debug("Unknown event type in sync call: {}", event.getClass().getSimpleName());
            }
        }), result::completeExceptionally, null);

        int timeout = entry.timeoutSeconds();
        String answer;
        try {
            answer = result.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RemoteAgentException(RemoteAgentException.CODE_REMOTE_TIMEOUT,
                    "Remote agent '" + call.agentId() + "' timed out after " + timeout + "s", e);
        } catch (InterruptedException e) {
            throw new RemoteAgentException(RemoteAgentException.CODE_REMOTE_ERROR,
                    "Interrupted while waiting for remote agent '" + call.agentId() + "'", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                throw rie;
            }
            throw new RemoteAgentException(RemoteAgentException.CODE_REMOTE_ERROR,
                    "Remote agent '" + call.agentId() + "' failed", e.getCause());
        }
        if (!observer.isCancelled()) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, answer));
            observer.onComplete();
        }
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
            // The A2A SDK discovers transports with ServiceLoader and the current
            // context class loader; common-pool threads may not see nested boot jars.
            thread.setContextClassLoader(appCl);
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /**
     * Streaming: forwards every chunk to the caller's stream verbatim and
     * additionally taps the answer as the tool result.
     *
     * <p>Transparency rule: in SSE mode every remote chunk (the raw
     * {@code {type,index,payload}} envelope) is forwarded to the caller's stream
     * unchanged, the final answer included — it is not consumed from the stream,
     * only tapped. The answer is discriminated by the envelope's own
     * {@code type} field: {@code type == "answer"} → its business text also
     * completes the future fed back to our LLM.
     */
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
        // Forward first so the answer chunk reaches the client's stream before the
        // future completes (which lets the delegating flow proceed to feed our LLM).
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, raw));
        RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(answer -> {
            log.info("Remote answer artifact ({} chars)", answer.length());
            if (!result.isDone()) {
                result.complete(answer);
            }
        });
    }

    /**
     * Handles {@link TaskStatusUpdateEvent}: INPUT_REQUIRED or
     * final-without-answer.
     */
    private void handleStatusUpdate(TaskStatusUpdateEvent sue, CompletableFuture<String> result) {
        if (sue.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = sue.status().message() != null ? extractText(sue.status().message().parts()) : "";
            log.info("A2A remote INPUT_REQUIRED taskId={} statusText={}", sue.taskId(), statusText);
            handleInputRequired(result, sue.taskId(), statusText);
        } else if (sue.status().state().isFinal() && !result.isDone()) {
            result.complete("");
        } else {
            log.debug("Intermediate status state: {}", sue.status().state());
        }
    }

    /**
     * Handles {@link TaskEvent}: fallback when stream ends without explicit answer
     * artifact (streaming) or the terminal task event (sync).
     */
    private void handleTaskEvent(TaskEvent te, CompletableFuture<String> result) {
        if (result.isDone()) {
            return;
        }
        Task task = te.getTask();
        if (task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = task.status().message() != null ? extractText(task.status().message().parts()) : "";
            log.info("A2A remote INPUT_REQUIRED taskId={} statusText={}", task.id(), statusText);
            handleInputRequired(result, task.id(), statusText);
        } else if (task.status().state().isFinal()) {
            String text = task.artifacts() != null && !task.artifacts().isEmpty()
                    ? extractText(task.artifacts().get(0).parts())
                    : "";
            log.info("A2A remote result ({} chars)", text.length());
            result.complete(text);
        } else {
            log.debug("Intermediate task state: {}", task.status().state());
        }
    }

    /**
     * Completes the future exceptionally with a
     * {@link RemoteInputRequiredException} if not already done.
     */
    private static void handleInputRequired(CompletableFuture<String> future, String remoteTaskId,
            String statusText) {
        if (future.isDone()) {
            return;
        }
        future.completeExceptionally(
                new RemoteInputRequiredException(statusText.isBlank() ? "Remote agent requires input" : statusText,
                        remoteTaskId != null ? remoteTaskId : ""));
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
