/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.controller.a2a.AgentCoreEnvelopeText;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import jakarta.annotation.PreDestroy;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
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
 * Baseline {@link RemoteAgentCaller} using the official A2A SDK
 * {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * <p>Exposes a single {@link #callOutcome(RemoteCall, QueryStreamObserver, Consumer)}
 * entry point used by {@code RemoteInvocationBatchCoordinator} for both single-agent
 * and parallel batch remote invocations. Streaming artifacts are forwarded to the
 * optional {@code streamObserver}; the structured {@link RemoteCallOutcome} carries
 * the terminal task state, the resolved business text, and any input-required
 * prompt back to the coordinator.
 *
 * <p>Structured failure handling: transport errors, timeouts, and premature
 * stream close complete the returned future exceptionally; the coordinator
 * translates them into per-member batch failures.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient implements RemoteAgentCaller {
    static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";

    static final String CALLBACK_TOKEN_METADATA = "runtime.a2a.callbackToken";

    static final String CALLBACK_ID_METADATA = "runtime.a2a.callbackId";

    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);
    private static final int DEFAULT_IO_CONCURRENCY = 16;

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<ClientCacheKey, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService ioExecutor;

    /**
     * Constructs the remote agent client with the default I/O concurrency.
     *
     * @param registry the remote agent card registry
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this(registry, DEFAULT_IO_CONCURRENCY);
    }

    /**
     * Constructs a remote client with a bounded executor for blocking SDK calls.
     *
     * @param registry the remote agent card registry
     * @param ioConcurrency maximum concurrent blocking SDK calls
     */
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
                    thread.setUncaughtExceptionHandler((source, error) -> log
                            .error("Uncaught A2A remote I/O error thread={}", source.getName(), error));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Parameter object bundling the result of {@link #prepareCall}.
     *
     * @param entry
     *            the resolved remote agent entry
     * @param params the built SDK send parameters
     * @param contextId
     *            the context/conversation ID
     */
    private record RemoteCallSetup(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry, MessageSendParams params,
            String contextId) {
    }

    private record TaskOutcome(String taskId, TaskState state, String statusText, Task task) {
    }

    /**
     * Resolves the remote agent entry and builds the SDK message.
     *
     * @param call remote call coordinates
     * @return the prepared call setup
     */
    private RemoteCallSetup prepareCall(RemoteCall call) {
        var entry = registry.get(call.agentName())
                .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + call.agentName()));
        var contextId = call.contextId() != null ? call.contextId() : java.util.UUID.randomUUID().toString();
        return new RemoteCallSetup(entry, buildSendParams(call, contextId), contextId);
    }

    static MessageSendParams buildSendParams(RemoteCall call, String contextId) {
        var messageBuilder = Message.builder().role(Message.Role.ROLE_USER).contextId(contextId)
                .parts(List.<Part<?>>of(new TextPart(call.message()))).metadata(call.messageMetadata());
        if (call.taskId() != null && !call.taskId().isBlank()) {
            messageBuilder.taskId(call.taskId());
        }
        Map<String, Object> paramsMetadata = paramsMetadata(call.metadata());
        var configurationBuilder = MessageSendConfiguration.builder().returnImmediately(false);
        callbackConfig(call, contextId).ifPresent(config -> configurationBuilder
                .returnImmediately(true)
                .taskPushNotificationConfig(config));
        return MessageSendParams.builder().message(messageBuilder.build()).configuration(configurationBuilder.build())
                .metadata(paramsMetadata).build();
    }

    private static Map<String, Object> paramsMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove(CALLBACK_URL_METADATA);
        result.remove(CALLBACK_TOKEN_METADATA);
        result.remove(CALLBACK_ID_METADATA);
        return result;
    }

    private static Optional<TaskPushNotificationConfig> callbackConfig(RemoteCall call, String contextId) {
        Object rawUrl = call.metadata().get(CALLBACK_URL_METADATA);
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            return Optional.empty();
        }
        String id = Optional.ofNullable(call.metadata().get(CALLBACK_ID_METADATA))
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse("push-" + contextId);
        String token = Optional.ofNullable(call.metadata().get(CALLBACK_TOKEN_METADATA))
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(null);
        return Optional.of(TaskPushNotificationConfig.builder().id(id).url(url).token(token).build());
    }

    /**
     * Creates or retrieves a cached SDK {@link Client} for the given card and
     * streaming mode.
     *
     * @param entry the registered remote agent entry
     * @param isStreaming
     *            whether the client should be in streaming mode
     * @return the SDK client
     */
    private Client createClient(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry, boolean isStreaming) {
        AgentCard card = entry.card();
        ClientCacheKey key = new ClientCacheKey(entry.name(), endpoint(card), isStreaming);
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(key,
                ignored -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
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
    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver) {
        A2ARemoteAgentCardRegistry.RemoteAgentEntry entry = registry.get(call.agentName())
                .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + call.agentName()));
        boolean isStreaming = entry.isStreaming() && call.isCallerStreaming();
        return callOutcome(call, streamObserver, remoteTaskIdObserver, isStreaming);
    }

    private CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, QueryStreamObserver streamObserver,
            Consumer<String> remoteTaskIdObserver, boolean isStreaming) {
        var setup = prepareCall(call);
        log.info("A2A call agent={} streaming={} taskId={} contextId={} textLen={}", call.agentName(), isStreaming,
                call.taskId() != null ? call.taskId() : "new", setup.contextId,
                call.message() != null ? call.message().length() : 0);

        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        boolean isCallbackMode = setup.params.configuration() != null
                && setup.params.configuration().taskPushNotificationConfig() != null;
        BiConsumer<ClientEvent, AgentCard> eventConsumer = (event, ignoredCard) ->
                handleClientEvent(event, result, streamObserver, remoteTaskIdObserver, isCallbackMode);
        Client client = createClient(setup.entry, isStreaming);
        AtomicReference<Future<?>> invocationTask = new AtomicReference<>();
        try {
            Future<?> submitted = ioExecutor.submit(() -> {
                try {
                    withApplicationClassLoader(() -> {
                        client.sendMessage(setup.params, List.of(eventConsumer),
                                error -> completeOutcomeOnStreamEnd(call.agentName(), result, error), null);
                        return null;
                    });
                } catch (RuntimeException ex) {
                    result.completeExceptionally(ex);
                }
            });
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

    @Override
    public boolean supported(String agentName) {
        return agentName != null && registry.get(agentName).isPresent();
    }

    private void handleClientEvent(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver, boolean isCallbackMode) {
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                notifyRemoteTaskId(remoteTaskIdObserver, aue.taskId(), tue.getTask().status().state());
                handleOutcomeArtifact(aue, result, streamObserver);
            } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                handleOutcomeStatus(sue, tue.getTask(), result, remoteTaskIdObserver, isCallbackMode);
            } else {
                log.debug("Unknown update event type: {}", tue.getUpdateEvent().getClass().getSimpleName());
            }
        } else if (event instanceof TaskEvent te) {
            handleOutcomeTask(te, result, remoteTaskIdObserver, isCallbackMode);
        } else if (event instanceof MessageEvent me) {
            handleOutcomeMessage(me, result, remoteTaskIdObserver);
        } else {
            log.debug("Unknown event type: {}", event.getClass().getSimpleName());
        }
    }

    private static boolean completeOutcomeOnStreamEnd(String agentName, CompletableFuture<?> result, Throwable error) {
        if (result.isDone()) {
            return false;
        }
        Throwable failure = error == null
                ? new IllegalStateException(
                        "Remote agent '" + agentName + "' closed the stream before a terminal event")
                : error;
        return result.completeExceptionally(failure);
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
        if (streamObserver != null) {
            for (Part<?> part : artifact.parts()) {
                if (part instanceof TextPart textPart && !textPart.text().isEmpty()) {
                    streamObserver.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, textPart.text()));
                    continue;
                }
                if (part instanceof DataPart dataPart) {
                    streamObserver.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, dataPart.data()));
                }
            }
        }
    }

    private void handleOutcomeStatus(TaskStatusUpdateEvent event, Task task,
            CompletableFuture<RemoteCallOutcome> result, Consumer<String> remoteTaskIdObserver,
            boolean isCallbackMode) {
        TaskState state = event.status().state();
        String statusText = event.status().message() != null ? extractText(event.status().message().parts()) : "";
        completeTaskOutcome(new TaskOutcome(event.taskId(), state, statusText, task), result,
            remoteTaskIdObserver, isCallbackMode);
    }

    private void handleOutcomeTask(TaskEvent event, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> remoteTaskIdObserver, boolean isCallbackMode) {
        Task task = event.getTask();
        TaskState state = task.status().state();
        String statusText = task.status().message() != null ? extractText(task.status().message().parts()) : "";
        completeTaskOutcome(new TaskOutcome(task.id(), state, statusText, task), result,
            remoteTaskIdObserver, isCallbackMode);
    }

    private static void completeTaskOutcome(TaskOutcome outcome, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> remoteTaskIdObserver) {
        completeTaskOutcome(outcome, result, remoteTaskIdObserver, false);
    }

    private static void completeTaskOutcome(TaskOutcome outcome, CompletableFuture<RemoteCallOutcome> result,
            Consumer<String> remoteTaskIdObserver, boolean isCallbackMode) {
        notifyRemoteTaskId(remoteTaskIdObserver, outcome.taskId(), outcome.state());
        if (result.isDone()) {
            return;
        }
        if (isCallbackMode && !outcome.state().isFinal()) {
            result.complete(new RemoteCallOutcome(outcome.taskId(), TaskState.TASK_STATE_INPUT_REQUIRED,
                    "INPUT_REQUIRED", null, "Remote callback pending"));
            return;
        }
        if (outcome.state().isInterrupted()) {
            String inputPrompt = outcome.statusText().isBlank() ? "Remote agent requires input" : outcome.statusText();
            result.complete(new RemoteCallOutcome(outcome.taskId(), outcome.state(), resultCategory(outcome.state()),
                    null, inputPrompt));
            return;
        }
        if (!outcome.state().isFinal()) {
            return;
        }
        String taskText = outcome.task() == null ? "" : extractTaskResult(outcome.task());
        String resultText = outcome.state() == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? outcome.statusText() : taskText)
                : (outcome.statusText().isBlank() ? taskText : outcome.statusText());
        result.complete(new RemoteCallOutcome(outcome.taskId(), outcome.state(), resultCategory(outcome.state()),
                resultText, null));
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

    /**
     * Closes cached SDK transports and stops the bounded I/O executor.
     */
    @PreDestroy
    public void shutdown() {
        ioExecutor.shutdownNow();
        Set<Client> clients = new LinkedHashSet<>(clientCache.values());
        clientCache.clear();
        clients.forEach(client -> {
            try {
                client.close();
            } catch (A2AException | IllegalStateException ex) {
                log.warn("Failed to close cached A2A client", ex);
            }
        });
    }

    private record ClientCacheKey(String agentName, String endpoint, boolean isStreaming) {
    }

    private static void notifyRemoteTaskId(Consumer<String> observer, String remoteTaskId, TaskState state) {
        if (observer == null) {
            return;
        }
        try {
            observer.accept(remoteTaskId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
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
     * Interprets an artifact's raw text as an AgentCore terminal stream envelope.
     * Recognized final types are unwrapped to their business text, falling back to
     * the raw text when the payload carries no recognizable text field. Other
     * envelope types return empty so callers can preserve them as streaming chunks.
     *
     * @param raw
     *            the artifact's concatenated text (a JSON envelope, or plain text)
     * @return the terminal business text, or empty if this is not a final envelope
     */
    static Optional<String> answerText(String raw) {
        return AgentCoreEnvelopeText.terminalText(raw);
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
        return AgentCoreEnvelopeText.businessText(data);
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
