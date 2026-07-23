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
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A2A remote agent caller using the official SDK {@code
 * Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient {
    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);

    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    /**
     * AgentCore stream-envelope {@code type} value that marks the final answer
     * chunk.
     */
    private static final String ANSWER_ENVELOPE_TYPE = "answer";

    private static final int DEFAULT_IO_CONCURRENCY = 16;

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<ClientCacheKey, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService ioExecutor;

    /**
     * Constructs the remote agent client.
     *
     * @param registry
     *            the remote agent card registry
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this(registry, DEFAULT_IO_CONCURRENCY);
    }

    /** Constructs a remote client with a bounded executor for blocking SDK calls. */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry, int ioConcurrency) {
        if (ioConcurrency <= 0) {
            throw new IllegalArgumentException("ioConcurrency must be greater than zero");
        }
        this.registry = registry;
        AtomicInteger threadIndex = new AtomicInteger();
        this.ioExecutor = new ThreadPoolExecutor(ioConcurrency, ioConcurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(ioConcurrency), runnable -> {
                Thread thread = new Thread(runnable, "a2a-remote-io-" + threadIndex.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
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
            Map<String, Object> metadata) {
    }

    /** Structured terminal or input-required result for a coordinator-owned call. */
    public record RemoteCallOutcome(String remoteTaskId, TaskState remoteState, String resultCategory, String result,
            String inputPrompt) {
    }

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
            Map<String, Object> metadata) {
    }

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
        var msgBuilder = Message.builder().role(Message.Role.ROLE_USER).contextId(ctxId)
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
    private Client createClient(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry, boolean isStreaming) {
        AgentCard card = entry.card();
        ClientCacheKey key = new ClientCacheKey(entry.name(), endpoint(card), isStreaming);
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(key,
                ignored -> Client.builder(card).clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig()).build()));
    }

    private static String endpoint(AgentCard card) {
        if (card.supportedInterfaces() != null && !card.supportedInterfaces().isEmpty()
                && card.supportedInterfaces().get(0).url() != null) {
            return card.supportedInterfaces().get(0).url();
        }
        return card.url() == null ? "" : card.url();
    }

    private static <T> T withApplicationClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader applicationClassLoader = A2ARemoteAgentClient.class.getClassLoader();
        if (applicationClassLoader == null || original == applicationClassLoader) {
            return action.get();
        }
        try {
            // The A2A SDK discovers transports with ServiceLoader and the current
            // context class loader; common-pool threads may not see nested boot jars.
            thread.setContextClassLoader(applicationClassLoader);
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /**
     * Calls a remote agent and preserves task routing and terminal-state details for
     * the runtime batch coordinator.
     *
     * @param call remote call coordinates
     * @param streamObserver observer for ordinary remote progress
     * @param remoteTaskIdObserver observer for remote task IDs used by batch persistence
     * @return structured remote outcome
     */
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver) {
        var setup = prepareCall(call.agentName(), call.message(), call.contextId(), call.taskId(), call.metadata());
        log.info("A2A call agent={} streaming={} taskId={} contextId={} textLen={}", call.agentName(),
                setup.entry.streaming(),
                call.taskId() != null ? call.taskId() : "new", setup.contextId,
                call.message() != null ? call.message().length() : 0);

        Client client = createClient(setup.entry, setup.entry.streaming());
        var configuration = MessageSendConfiguration.builder().returnImmediately(false).build();
        var params = MessageSendParams.builder().message(setup.message).configuration(configuration)
                .metadata(setup.metadata).build();
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        AtomicReference<Future<?>> invocationTask = new AtomicReference<>();
        BiConsumer<ClientEvent, AgentCard> eventConsumer = (event, ignoredCard) -> {
            if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                    notifyRemoteTaskId(remoteTaskIdObserver, aue.taskId(), tue.getTask().status().state());
                    handleOutcomeArtifact(aue, result, streamObserver);
                } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                    handleOutcomeStatus(sue, tue.getTask(), result, remoteTaskIdObserver);
                } else {
                    log.debug("Unknown update event type: {}", tue.getUpdateEvent().getClass().getSimpleName());
                }
            } else if (event instanceof TaskEvent te) {
                handleOutcomeTask(te, result, remoteTaskIdObserver);
            } else if (event instanceof MessageEvent me) {
                handleOutcomeMessage(me, result, remoteTaskIdObserver);
            } else {
                log.debug("Unknown event type: {}", event.getClass().getSimpleName());
            }
        };
        try {
            Future<?> submitted = ioExecutor.submit(() -> withApplicationClassLoader(() -> {
                client.sendMessage(params, List.of(eventConsumer), result::completeExceptionally, null);
                return null;
            }));
            invocationTask.set(submitted);
            if (result.isDone()) {
                submitted.cancel(true);
            }
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(ex);
        }
        result.whenComplete((outcome, error) -> {
            if (error != null || result.isCancelled()) {
                Future<?> submitted = invocationTask.get();
                if (submitted != null && !submitted.isDone()) {
                    submitted.cancel(true);
                }
            }
        });
        return result;
    }

    private void handleOutcomeArtifact(TaskArtifactUpdateEvent event, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver) {
        if (result.isDone()) {
            return;
        }
        Artifact artifact = event.artifact();
        if (artifact == null || artifact.parts() == null) {
            return;
        }
        String raw = extractText(artifact.parts());
        if (raw.isEmpty()) {
            return;
        }
        if (streamObserver != null) {
            streamObserver.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, raw));
        }
    }

    private void handleOutcomeStatus(TaskStatusUpdateEvent event, Task task,
            CompletableFuture<RemoteCallOutcome> result, Consumer<String> remoteTaskIdObserver) {
        TaskState state = event.status().state();
        String statusText = event.status().message() != null ? extractText(event.status().message().parts()) : "";
        completeTaskOutcome(event.taskId(), state, statusText, task, result, remoteTaskIdObserver);
    }

    private void handleOutcomeTask(TaskEvent event, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> remoteTaskIdObserver) {
        Task task = event.getTask();
        TaskState state = task.status().state();
        String statusText = task.status().message() != null ? extractText(task.status().message().parts()) : "";
        completeTaskOutcome(task.id(), state, statusText, task, result, remoteTaskIdObserver);
    }

    private static void completeTaskOutcome(String taskId, TaskState state, String statusText, Task task,
            CompletableFuture<RemoteCallOutcome> result, Consumer<String> remoteTaskIdObserver) {
        notifyRemoteTaskId(remoteTaskIdObserver, taskId, state);
        if (result.isDone()) {
            return;
        }
        if (state.isInterrupted()) {
            result.complete(new RemoteCallOutcome(taskId, state, resultCategory(state), null,
                statusText.isBlank() ? "Remote agent requires input" : statusText));
        } else if (state.isFinal()) {
            String taskText = task == null ? "" : extractTaskResult(task);
            String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? statusText : taskText)
                : (statusText.isBlank() ? taskText : statusText);
            result.complete(new RemoteCallOutcome(taskId, state, resultCategory(state), resultText, null));
        }
    }

    private void handleOutcomeMessage(MessageEvent event, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> remoteTaskIdObserver) {
        if (result.isDone() || event.getMessage() == null) {
            return;
        }
        Message message = event.getMessage();
        notifyRemoteTaskId(remoteTaskIdObserver, message.taskId(), TaskState.TASK_STATE_COMPLETED);
        result.complete(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
            extractBusinessParts(message.parts()), null));
    }

    private static String extractTaskResult(Task task) {
        if (task.artifacts() == null || task.artifacts().isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        String answer = null;
        for (Artifact artifact : task.artifacts()) {
            String artifactText = extractBusinessParts(artifact.parts());
            if (artifactText.isEmpty()) {
                continue;
            }
            Optional<String> parsedAnswer = answerText(artifactText);
            if (parsedAnswer.isPresent()) {
                answer = parsedAnswer.get();
            } else {
                content.append(artifactText);
            }
        }
        return answer != null ? answer : content.toString();
    }

    private static String extractBusinessParts(List<Part<?>> parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart
                    && (textPart.metadata() == null || !textPart.metadata().containsKey("_remote_invocation"))) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    /** Closes cached SDK transports and stops the bounded I/O executor. */
    @PreDestroy
    public void shutdown() {
        ioExecutor.shutdownNow();
        Set<Client> clients = new LinkedHashSet<>(clientCache.values());
        clientCache.clear();
        clients.forEach(client -> {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close cached A2A client", ex);
            }
        });
    }

    private record ClientCacheKey(String agentName, String endpoint, boolean streaming) {
    }

    private static void notifyRemoteTaskId(Consumer<String> observer, String remoteTaskId, TaskState state) {
        if (observer == null) {
            return;
        }
        try {
            observer.accept(remoteTaskId);
        } catch (RuntimeException ex) {
            log.warn("Remote task ID observer rejected update taskId={} state={}", remoteTaskId, state, ex);
        }
    }

    static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_PROTOCOL_ERROR";
        };
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
     * nested {@code payload} map over the top level.
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
