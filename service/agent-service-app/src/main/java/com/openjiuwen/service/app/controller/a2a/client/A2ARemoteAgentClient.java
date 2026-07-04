/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
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

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A2A remote agent caller using the official SDK {@code
 * Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient {
    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);

    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /**
     * AgentCore stream-envelope {@code type} value that marks the final answer
     * chunk.
     */
    private static final String ANSWER_ENVELOPE_TYPE = "answer";

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<String, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructs the remote agent client.
     *
     * @param registry
     *            the remote agent card registry
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    /**
     * Parameter object for a remote agent call: the addressing and payload
     * coordinates shared by callers.
     *
     * @param agentName
     *            registered remote agent name
     * @param message
     *            text payload to send
     * @param contextId
     *            conversation context ID (shared across calls to the same remote)
     * @param taskId
     *            remote task ID to resume, or null for a new task
     * @param metadata
     *            additional metadata for the call
     */
    public record RemoteCall(String agentName, String message, String contextId, String taskId,
                            Map<String, Object> metadata) {}

    /**
     * Parameter object bundling the result of {@link #prepareCall}.
     *
     * @param entry
     *            the resolved remote agent entry
     * @param message
     *            the built SDK message
     * @param contextId
     *            the context/conversation ID
     * @param metadata
     *            the metadata map
     */
    private record RemoteCallSetup(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry, Message message, String contextId,
                                    Map<String, Object> metadata) {}

    /**
     * Resolves the remote agent entry and builds the SDK message.
     *
     * @param agentName
     *            the remote agent name
     * @param message
     *            the text payload
     * @param contextId
     *            the context/conversation ID
     * @param taskId
     *            the optional task ID for resume
     * @param metadata
     *            the metadata map
     * @return the prepared call setup
     */
    private RemoteCallSetup prepareCall(String agentName, String message, String contextId, String taskId,
        Map<String, Object> metadata) {
        var entry = registry.get(agentName)
            .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + agentName));
        var ctxId = contextId != null ? contextId : java.util.UUID.randomUUID().toString();
        var msgBuilder = Message.builder()
            .role(Message.Role.ROLE_USER)
            .contextId(ctxId)
            .parts(List.<Part<?>>of(new TextPart(message)));
        if (taskId != null && !taskId.isBlank()) {
            msgBuilder.taskId(taskId);
        }
        return new RemoteCallSetup(entry, msgBuilder.build(), ctxId, metadata);
    }

    /**
     * Creates or retrieves a cached SDK {@link Client} for the given card and
     * streaming mode.
     *
     * @param card
     *            the agent card
     * @param isStreaming
     *            whether the client should be in streaming mode
     * @return the SDK client
     */
    private Client createClient(AgentCard card, boolean isStreaming) {
        return clientCache.computeIfAbsent(card.name() + ":" + isStreaming, k -> Client.builder(card)
            .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
            .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
            .build());
    }

    /**
     * Completes the future exceptionally with a
     * {@link RemoteInputRequiredException} if not already done.
     *
     * @param future
     *            the future to complete
     * @param remoteTaskId
     *            the remote task ID
     * @param statusText
     *            the status message text
     */
    private static void handleInputRequired(CompletableFuture<String> future, String remoteTaskId, String statusText) {
        if (future.isDone()) {
            return;
        }
        future.completeExceptionally(
            new RemoteInputRequiredException(statusText.isBlank() ? "Remote agent requires input" : statusText,
                remoteTaskId != null ? remoteTaskId : ""));
    }

    /**
     * Call a remote agent via streaming SendMessage. Streaming chunks are forwarded
     * verbatim to streamObserver; the chunk whose envelope type is "answer" is
     * captured as the final result.
     *
     * @param call
     *            the remote call coordinates (agent, message, context, optional
     *            resume task, metadata)
     * @param streamObserver
     *            observer for forwarding streaming chunks
     * @return future resolving to the final-answer text
     */
    public CompletableFuture<String> callStreaming(RemoteCall call, QueryStreamObserver streamObserver) {
        var setup = prepareCall(call.agentName(), call.message(), call.contextId(), call.taskId(), call.metadata());
        log.info("A2A streaming call agent={} taskId={} contextId={} textLen={}", call.agentName(),
            call.taskId() != null ? call.taskId() : "new", setup.contextId,
            call.message() != null ? call.message().length() : 0);

        Client client = createClient(setup.entry.card(), true);
        var params = MessageSendParams.builder().message(setup.message).metadata(setup.metadata).build();
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
            if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                    handleArtifact(aue, result, streamObserver);
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
        }), result::completeExceptionally, null);

        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        return result;
    }

    /**
     * Streaming: forwards every chunk to the caller's stream verbatim and
     * additionally taps the answer as the tool result.
     *
     * <p>
     * Transparency rule: in SSE mode every remote chunk (the raw
     * {@code {type,index,payload}} envelope) is forwarded to the caller's stream
     * unchanged, the final answer included — it is not consumed from the stream,
     * only tapped. Sync callers pass no observer, so nothing is forwarded (see
     * {@link #callSync}). The answer is discriminated by the envelope's own
     * {@code type} field, not by rewriting the payload upstream:
     * {@code type == "answer"} → its business text also completes the future fed
     * back to our LLM.
     *
     * @param aue
     *            the task artifact update event
     * @param result
     *            the result future to complete with the answer text
     * @param streamObserver
     *            the observer for forwarding streaming chunks
     */
    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<String> result,
        QueryStreamObserver streamObserver) {
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) {
            return;
        }
        String raw = extractText(a.parts());
        if (raw.isEmpty()) {
            return;
        }
        // Forward first so the answer chunk reaches the client's stream before the
        // future
        // completes (which lets the delegating flow proceed to feed our LLM).
        streamObserver.onNext(new QueryChunk("chunk", raw));
        answerText(raw).ifPresent(answer -> {
            log.info("Remote answer artifact ({} chars)", answer.length());
            if (!result.isDone()) {
                result.complete(answer);
            }
        });
    }

    /**
     * Interprets an artifact's raw text as an AgentCore stream envelope: if it is
     * the final answer ({@code type == "answer"}), returns the unwrapped business
     * text (falling back to the raw text when the payload carries no recognizable
     * text field); otherwise returns empty so the caller forwards it as a streaming
     * chunk.
     *
     * @param raw
     *            the artifact's concatenated text (a JSON envelope, or plain text)
     * @return the answer's business text, or empty if this is not an answer
     *         envelope
     */
    static Optional<String> answerText(String raw) {
        return parseEnvelope(raw).filter(envelope -> ANSWER_ENVELOPE_TYPE.equals(envelope.get("type")))
            .map(envelope -> extractBusinessText(envelope).orElse(raw));
    }

    /**
     * Parses a JSON object string into a map, or returns empty if it is not a JSON
     * object (e.g. plain text or a JSON null).
     *
     * @param raw
     *            the candidate JSON string
     * @return the parsed map, or empty
     */
    private static Optional<Map<String, Object>> parseEnvelope(String raw) {
        try {
            return Optional.ofNullable(GSON.fromJson(raw, MAP_TYPE));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Extracts the business text from a normalized chunk payload, preferring the
     * nested {@code payload} map over the top level, mirroring the sync path's
     * content extraction.
     *
     * @param data
     *            the chunk data
     * @return the business text, or empty if the chunk carries no text field
     */
    static Optional<String> extractBusinessText(Object data) {
        if (data instanceof String s) {
            return s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<String> fromPayload = map.get("payload") instanceof Map<?, ?> payload
            ? firstText(payload)
            : Optional.empty();
        return fromPayload.isPresent() ? fromPayload : firstText(map);
    }

    /**
     * Returns the first non-blank scalar value among the known text keys
     * ({@code content}, {@code delta}, {@code output}, {@code response}).
     *
     * @param map
     *            the map to scan
     * @return the first text value, or empty if none present
     */
    private static Optional<String> firstText(Map<?, ?> map) {
        for (String key : List.of("content", "delta", "output", "response")) {
            Object value = map.get(key);
            if (value == null || value instanceof Map || value instanceof List) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    /**
     * Handles {@link TaskStatusUpdateEvent}: INPUT_REQUIRED or
     * final-without-answer.
     *
     * @param sue
     *            the task status update event
     * @param result
     *            the result future
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
     * artifact.
     *
     * @param te
     *            the task event
     * @param result
     *            the result future
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
            String text = task.artifacts() != null && !task.artifacts().isEmpty() ? extractText(
                task.artifacts().get(0).parts()) : "";
            log.info("A2A remote result ({} chars)", text.length());
            result.complete(text);
        } else {
            log.debug("Intermediate task state: {}", task.status().state());
        }
    }

    /**
     * Call a remote agent via non-streaming SendMessage (synchronous). Blocks until
     * the remote agent completes or requires input.
     *
     * @param agentName
     *            registered remote agent name
     * @param message
     *            text payload to send
     * @param contextId
     *            conversation context ID
     * @param taskId
     *            remote task ID to resume, or null for a new task
     * @param metadata
     *            additional metadata for the call
     * @return the final-answer text from the remote agent
     * @throws RemoteInputRequiredException
     *             if the remote agent requires user input
     */
    public String callSync(String agentName, String message, String contextId, String taskId,
        Map<String, Object> metadata) throws RemoteInputRequiredException {
        var setup = prepareCall(agentName, message, contextId, taskId, metadata);
        log.info("A2A sync call agent={} taskId={} contextId={} textLen={}", agentName, taskId != null ? taskId : "new",
            setup.contextId, message != null ? message.length() : 0);

        Client client = createClient(setup.entry.card(), false);
        var params = MessageSendParams.builder().message(setup.message).metadata(setup.metadata).build();
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
            if (event instanceof TaskEvent te) {
                handleTaskEvent(te, result);
            } else {
                log.debug("Unknown event type in sync call: {}", event.getClass().getSimpleName());
            }
        }), result::completeExceptionally, null);

        int timeout = setup.entry.timeoutSeconds();
        try {
            return result.get(timeout, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RemoteAgentException("Remote agent '" + agentName + "' timed out after " + timeout + "s", e);
        } catch (InterruptedException e) {
            throw new RemoteAgentException("Interrupted while waiting for remote agent '" + agentName + "'", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                throw rie;
            }
            throw new RemoteAgentException("Remote agent '" + agentName + "' failed", e.getCause());
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

    /** Signal that the remote agent requires user input (INPUT_REQUIRED). */
    public static class RemoteInputRequiredException extends RuntimeException {
        private final String remoteTaskId;

        /**
         * Constructs the exception.
         *
         * @param message
         *            the error message
         * @param remoteTaskId
         *            the remote task ID
         */
        public RemoteInputRequiredException(String message, String remoteTaskId) {
            super(message);
            this.remoteTaskId = remoteTaskId;
        }

        /**
         * Returns the remote task ID associated with this input-required state.
         *
         * @return the remote task ID
         */
        public String getRemoteTaskId() {
            return remoteTaskId;
        }
    }

    /** Wraps remote agent call failures (timeout, interrupted, execution error). */
    public static class RemoteAgentException extends RuntimeException {
        /**
         * Constructs the exception.
         *
         * @param message
         *            the error message
         * @param cause
         *            the underlying cause
         */
        public RemoteAgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
