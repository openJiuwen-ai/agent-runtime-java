/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.controller.a2a.A2aErrorMetadata;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import jakarta.annotation.PreDestroy;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpClientFactory;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Baseline {@link RemoteAgentCaller} using the official A2A SDK
 * {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * <p>Exposes a single {@link #callOutcome(RemoteCall, RemoteAgentCaller.EventObserver)}
 * entry point used by {@code RemoteInvocationBatchCoordinator} for both single-agent
 * and parallel batch remote invocations. Complete streaming events are forwarded to the
 * optional {@code eventObserver}; the structured {@link RemoteCallOutcome} carries
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
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long CANCEL_POLL_INTERVAL_NANOS = 50_000_000L;

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<ClientCacheKey, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService ioExecutor;

    /**
     * Base delay for outbound transient-failure retries
     * (exponential backoff, {@value #MAX_RETRY_ATTEMPTS} retries). Doubles per attempt;
     * tests shrink it via reflection to keep the suite fast.
     */
    private volatile long retryBackoffBaseMillis = 200L;

    /**
     * Constructs the remote agent client with the default I/O concurrency.
     *
     * @param registry the remote agent card registry
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this(registry, DEFAULT_IO_CONCURRENCY);
    }

    /**
     * Constructs a remote client with an executor for blocking SDK calls.
     *
     * @param registry the remote agent card registry
     * @param ioConcurrency maximum concurrent blocking SDK calls on JDK 17
     */
    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry, int ioConcurrency) {
        if (ioConcurrency <= 0) {
            throw new IllegalArgumentException("ioConcurrency must be greater than zero");
        }
        this.registry = registry;
        this.ioExecutor = newIoExecutor(ioConcurrency);
    }

    private static ExecutorService newIoExecutor(int ioConcurrency) {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("a2a-remote-io",
                    (thread, error) -> log.error("Uncaught A2A remote I/O error thread={}", thread.getName(), error));
        }
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(ioConcurrency, ioConcurrency, 0L, TimeUnit.MILLISECONDS,
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
    private record RemoteCallSetup(RemoteAgentEntry entry, MessageSendParams params, String contextId) {
    }

    private record TaskOutcome(String taskId, TaskState state, String statusText, Task task,
            AgentFailureDescriptor remoteFailure) {
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
                .parts(outboundParts(call)).metadata(call.messageMetadata());
        if (call.taskId() != null && !call.taskId().isBlank()) {
            messageBuilder.taskId(call.taskId());
        }
        Map<String, Object> paramsMetadata = paramsMetadata(call.metadata());
        var configurationBuilder = MessageSendConfiguration.builder().returnImmediately(false);
        callbackConfig(call, contextId)
                .ifPresent(config -> configurationBuilder.returnImmediately(true).taskPushNotificationConfig(config));
        return MessageSendParams.builder().message(messageBuilder.build()).configuration(configurationBuilder.build())
                .metadata(paramsMetadata).build();
    }

    /**
     * Outbound part assembly: the legacy text payload stays the
     * leading part; normalized non-text parts follow in order; additional text parts are
     * appended after them so files keep their position ahead of trailing context text.
     * Inbound format is preserved (url in → FileWithUri out, raw in → FileWithBytes out).
     *
     * @param call the remote call carrying the normalized outbound parts
     * @return the assembled SDK parts with the leading text part
     */
    private static List<Part<?>> outboundParts(RemoteCall call) {
        List<Map<String, Object>> normalized = call.parts();
        if (normalized == null || normalized.isEmpty()) {
            return List.of(new TextPart(call.message()));
        }
        List<Part<?>> parts = new ArrayList<>(normalized.size() + 1);
        parts.add(new TextPart(call.message()));
        List<Part<?>> trailingText = new ArrayList<>();
        for (Map<String, Object> part : normalized) {
            Optional<Part<?>> mapped = toSdkPartOrEmpty(part);
            if (mapped.isEmpty()) {
                continue;
            }
            if (mapped.get() instanceof TextPart text) {
                trailingText.add(text);
            } else {
                parts.add(mapped.get());
            }
        }
        parts.addAll(trailingText);
        return parts;
    }

    /**
     * Maps one normalized part to its SDK representation.
     *
     * @param part the normalized part map (kind + payload fields)
     * @return the SDK part, or empty for unsupported kinds / non-string text payloads
     */
    private static Optional<Part<?>> toSdkPartOrEmpty(Map<String, Object> part) {
        String kind = String.valueOf(part.get("kind"));
        switch (kind) {
        case "url":
            return Optional.of(new FilePart(new FileWithUri(nonBlankString(part.get("mediaType")),
                    nonBlankString(part.get("filename")), nonBlankString(part.get("url")))));
        case "raw":
            return Optional.of(new FilePart(new FileWithBytes(nonBlankString(part.get("mediaType")),
                    nonBlankString(part.get("filename")), String.valueOf(part.get("bytesBase64")))));
        case "data":
            return Optional.of(new DataPart(part.get("data") instanceof Map<?, ?> data ? copyData(data) : Map.of()));
        case "text":
            return part.get("text") instanceof String text ? Optional.of(new TextPart(text)) : Optional.empty();
        default:
            return Optional.empty();
        }
    }

    private static Map<String, Object> copyData(Map<?, ?> data) {
        Map<String, Object> copy = new LinkedHashMap<>();
        data.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /**
     * Resolves an optional normalized part string field.
     *
     * @param value the raw field value
     * @return the non-blank string, or an empty string when absent/blank
     */
    private static String nonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() ? text : "";
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
        String id = Optional.ofNullable(call.metadata().get(CALLBACK_ID_METADATA)).map(String::valueOf)
                .filter(value -> !value.isBlank()).orElse("push-" + contextId);
        String token = Optional.ofNullable(call.metadata().get(CALLBACK_TOKEN_METADATA)).map(String::valueOf)
                .filter(value -> !value.isBlank()).orElse(null);
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
    private Client createClient(RemoteAgentEntry entry, boolean isStreaming) {
        AgentCard card = entry.card();
        ClientCacheKey key = new ClientCacheKey(entry.name(), endpoint(card), isStreaming);
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(key, ignored -> Client.builder(card)
                .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(createHttpClient())).build()));
    }

    /**
     * Builds the HTTP client for outbound A2A calls: the client selected by the SDK
     * provider mechanism ({@link A2AHttpClientFactory#create()}), decorated with
     * propagation-header injection. Custom {@code A2AHttpClientProvider} deployments
     * are therefore preserved; injection is a no-op until a provider is registered in
     * {@link A2APropagationHeaderRegistry}.
     *
     * @return the HTTP client to back the JSON-RPC transport
     */
    static A2AHttpClient createHttpClient() {
        return new HeaderInjectingA2AHttpClient(A2AHttpClientFactory.create());
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
     * @param eventObserver observer for complete remote A2A events
     * @return structured remote outcome
     */
    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver) {
        RemoteAgentEntry entry;
        try {
            entry = registry.get(call.agentName())
                    .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + call.agentName()));
        } catch (RuntimeException ex) {
            log.error("A2A remote call preparation failed agent={} streaming={} taskId={} contextId={}",
                    call.agentName(), call.isCallerStreaming(), call.taskId() != null ? call.taskId() : "new",
                    call.contextId(), ex);
            throw ex;
        } catch (Error error) {
            logRemoteError("call preparation", call, call.isCallerStreaming(), call.contextId(), error);
            throw error;
        }
        boolean isStreaming = entry.isStreaming() && call.isCallerStreaming();
        return callOutcome(call, eventObserver, isStreaming);
    }

    private CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver, boolean isStreaming) {
        RemoteCallSetup setup;
        try {
            setup = prepareCall(call);
        } catch (RuntimeException ex) {
            log.error("A2A remote call preparation failed agent={} streaming={} taskId={} contextId={}",
                    call.agentName(), isStreaming, call.taskId() != null ? call.taskId() : "new", call.contextId(), ex);
            throw ex;
        } catch (Error error) {
            logRemoteError("call preparation", call, isStreaming, call.contextId(), error);
            throw error;
        }
        log.info("A2A call agent={} streaming={} taskId={} contextId={} textLen={}", call.agentName(), isStreaming,
                call.taskId() != null ? call.taskId() : "new", setup.contextId,
                call.message() != null ? call.message().length() : 0);
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        Client client;
        try {
            client = createClient(setup.entry, isStreaming);
        } catch (RuntimeException ex) {
            log.error("A2A remote client creation failed agent={} streaming={} taskId={} contextId={}",
                    call.agentName(), isStreaming, call.taskId() != null ? call.taskId() : "new", setup.contextId, ex);
            throw ex;
        } catch (LinkageError error) {
            logRemoteError("client creation", call, isStreaming, setup.contextId, error);
            throw error;
        } catch (Error error) {
            logRemoteError("client creation", call, isStreaming, setup.contextId, error);
            throw error;
        }
        submitInvocation(call, setup, client, (event, ignoredCard) -> {
            try {
                handleClientEvent(event, result, eventObserver, hasTaskPushConfig(setup.params), isStreaming);
            } catch (RuntimeException ex) {
                log.error("A2A remote event failed agent={} streaming={} taskId={} contextId={}", call.agentName(),
                        isStreaming, call.taskId() != null ? call.taskId() : "new", setup.contextId, ex);
                result.completeExceptionally(ex);
            } catch (Error error) {
                logRemoteError("event", call, isStreaming, setup.contextId, error);
                result.completeExceptionally(error);
                throw error;
            }
        }, result);
        return result;
    }

    private void submitInvocation(RemoteCall call, RemoteCallSetup setup, Client client,
            BiConsumer<ClientEvent, AgentCard> eventConsumer, CompletableFuture<RemoteCallOutcome> result) {
        boolean isStreaming = setup.entry.isStreaming() && call.isCallerStreaming();
        try {
            AtomicReference<Future<?>> invocationTask = new AtomicReference<>(ioExecutor.submit(() -> {
                // transient transport failures (connection
                // refused, remote 5xx) are replayed with exponential backoff, capped at
                // MAX_RETRY_ATTEMPTS; the send params (including multimodal parts) are
                // re-sent verbatim, so the remote sees the identical payload per retry.
                int attempt = 0;
                while (true) {
                    try {
                        withApplicationClassLoader(() -> {
                            client.sendMessage(setup.params, List.of(eventConsumer),
                                    error -> completeOutcomeOnStreamEnd(call.agentName(), result, error), null);
                            return null;
                        });
                        return null;
                    } catch (Error error) {
                        logRemoteError("call", call, isStreaming, setup.contextId(), error);
                        result.completeExceptionally(error);
                        throw error;
                    } catch (RuntimeException ex) {
                        attempt++;
                        if (result.isDone() || attempt > MAX_RETRY_ATTEMPTS
                                || !awaitRetryBackoff(new RetryContext(call, isStreaming, setup.contextId(), result),
                                        attempt, ex)) {
                            log.error("A2A remote call failed agent={} streaming={} taskId={} contextId={} attempts={}",
                                    call.agentName(), isStreaming, call.taskId() != null ? call.taskId() : "new",
                                    setup.contextId(), attempt, ex);
                            result.completeExceptionally(ex);
                            return null;
                        }
                    }
                }
            }));
            if (result.isDone()) {
                invocationTask.get().cancel(true);
            }
            cancelInvocationOnCompletion(result, invocationTask);
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(ex);
        }
    }

    /** Retry-time coordinates shared by the backoff sleeper and its callers (for logging/completion). */
    private record RetryContext(RemoteCall call, boolean isStreaming, String contextId,
            CompletableFuture<RemoteCallOutcome> result) {
    }

    /**
     * Sleeps the exponential backoff slot before the next retry attempt. Returns
     * {@code false} (completing the future exceptionally with the original failure)
     * when the result future was completed or cancelled during the wait.
     *
     * @param retry the retry coordinates (call, mode, context, result future)
     * @param attempt the retry attempt number, starting at 1
     * @param failure the transient failure that triggered the retry
     * @return {@code true} to proceed with the retry, {@code false} when aborted
     */
    private boolean awaitRetryBackoff(RetryContext retry, int attempt, RuntimeException failure) {
        long backoffMillis = retryBackoffBaseMillis << (attempt - 1);
        log.warn("A2A remote transient failure agent={} streaming={} taskId={} contextId={} attempt={}/{} retryIn={}ms",
                retry.call().agentName(), retry.isStreaming(),
                retry.call().taskId() != null ? retry.call().taskId() : "new", retry.contextId(), attempt,
                MAX_RETRY_ATTEMPTS, backoffMillis, failure);
        // G.CON.10: cooperative cancellation — the sleeper periodically polls the
        // shared result future instead of relying on thread interruption; when the
        // future is already settled (cancelled or completed elsewhere) the retry
        // loop aborts and cleans up on its own.
        long deadline = System.nanoTime() + backoffMillis * 1_000_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (retry.result().isDone()) {
                log.debug("A2A retry backoff aborted, result already settled");
                return false;
            }
            LockSupport.parkNanos(Math.min(remaining, CANCEL_POLL_INTERVAL_NANOS));
        }
        return true;
    }

    private static void cancelInvocationOnCompletion(CompletableFuture<?> result,
            AtomicReference<Future<?>> invocationTask) {
        result.whenComplete((outcome, error) -> {
            if (error != null || result.isCancelled()) {
                Future<?> submitted = invocationTask.get();
                if (submitted != null && !submitted.isDone()) {
                    submitted.cancel(true);
                }
            }
        });
    }

    private static boolean hasTaskPushConfig(MessageSendParams params) {
        return params.configuration() != null && params.configuration().taskPushNotificationConfig() != null;
    }

    private static void logRemoteError(String operation, RemoteCall call, boolean isStreaming, String contextId,
            Error error) {
        String reason = error instanceof LinkageError
                ? "class linkage or initialization error; check effective POM and dependency tree for version conflicts"
                : "error";
        log.error("A2A remote {} failed due to {} agent={} streaming={} taskId={} contextId={}", operation, reason,
                call.agentName(), isStreaming, call.taskId() != null ? call.taskId() : "new", contextId, error);
    }

    private void handleClientEvent(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isCallbackMode, boolean isStreaming) {
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                if (!result.isDone()) {
                    eventObserver.onArtifact(aue);
                }
            } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                handleOutcomeStatus(sue, tue.getTask(), result, eventObserver, isCallbackMode);
            } else {
                log.debug("Unknown update event type: {}", tue.getUpdateEvent().getClass().getSimpleName());
            }
        } else if (event instanceof TaskEvent te) {
            handleOutcomeTask(te, result, eventObserver, isCallbackMode, isStreaming);
        } else if (event instanceof MessageEvent me) {
            handleOutcomeMessage(me, result);
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

    private void handleOutcomeStatus(TaskStatusUpdateEvent event, Task task,
            CompletableFuture<RemoteCallOutcome> result, RemoteAgentCaller.EventObserver eventObserver,
            boolean isCallbackMode) {
        if (result.isDone()) {
            return;
        }
        TaskState state = event.status().state();
        eventObserver.onStatus(event);
        String statusText = event.status().message() != null ? extractText(event.status().message().parts()) : "";
        completeTaskOutcome(new TaskOutcome(event.taskId(), state, statusText, task,
                remoteFailure(event.status().message()).orElse(null)), result, isCallbackMode);
    }

    private void handleOutcomeTask(TaskEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isCallbackMode, boolean isStreaming) {
        if (result.isDone()) {
            return;
        }
        Task task = event.getTask();
        TaskState state = task.status().state();
        if (isStreaming) {
            eventObserver.onStatus(new TaskStatusUpdateEvent(task.id(), task.status(), task.contextId(), Map.of()));
        }
        String statusText = task.status().message() != null ? extractText(task.status().message().parts()) : "";
        completeTaskOutcome(new TaskOutcome(task.id(), state, statusText, task,
                remoteFailure(task.status().message()).orElse(null)), result, isCallbackMode);
    }

    private static void completeTaskOutcome(TaskOutcome outcome, CompletableFuture<RemoteCallOutcome> result,
            boolean isCallbackMode) {
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
        String taskText = outcome.task() == null ? "" : A2aPartContent.extractTaskResult(outcome.task());
        String resultText = outcome.state() == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? outcome.statusText() : taskText)
                : (outcome.statusText().isBlank() ? taskText : outcome.statusText());
        result.complete(new RemoteCallOutcome(outcome.taskId(), outcome.state(), resultCategory(outcome.state()),
                resultText, null, outcome.remoteFailure()));
    }

    private void handleOutcomeMessage(MessageEvent event, CompletableFuture<RemoteCallOutcome> result) {
        if (result.isDone() || event.getMessage() == null) {
            return;
        }
        Message message = event.getMessage();
        result.complete(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
                A2aPartContent.extract(message.parts()), null));
    }

    /**
     * Closes cached SDK transports and stops the I/O executor.
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

    static String resultCategory(TaskState state) {
        if (state == TaskState.TASK_STATE_COMPLETED) {
            return "COMPLETED";
        }
        if (state.isInterrupted()) {
            return "INPUT_REQUIRED";
        }
        if (state == TaskState.TASK_STATE_FAILED) {
            return "REMOTE_BUSINESS_FAILURE";
        }
        return "REMOTE_" + state.name().replaceFirst("^TASK_STATE_", "");
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

    private static Optional<AgentFailureDescriptor> remoteFailure(Message message) {
        return message == null ? Optional.empty() : A2aErrorMetadata.decode(message.metadata());
    }
}
