/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

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
                .parts(List.<Part<?>>of(new TextPart(call.message()))).metadata(call.messageMetadata());
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
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(key,
                ignored -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(createHttpClient()))
                        .build()));
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
            RemoteAgentCaller.EventObserver eventObserver,
            boolean isStreaming) {
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
        submitInvocation(call, setup, client,
                (event, ignoredCard) -> {
                    try {
                        handleClientEvent(event, result, eventObserver, hasTaskPushConfig(setup.params), isStreaming);
                    } catch (RuntimeException ex) {
                        log.error("A2A remote event failed agent={} streaming={} taskId={} contextId={}",
                                call.agentName(), isStreaming, call.taskId() != null ? call.taskId() : "new",
                                setup.contextId, ex);
                        result.completeExceptionally(ex);
                    } catch (Error error) {
                        logRemoteError("event", call, isStreaming, setup.contextId, error);
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
                try {
                    withApplicationClassLoader(() -> {
                        client.sendMessage(setup.params, List.of(eventConsumer),
                                error -> completeOutcomeOnStreamEnd(call.agentName(), result, error), null);
                        return null;
                    });
                } catch (RuntimeException ex) {
                    log.error("A2A remote call failed agent={} streaming={} taskId={} contextId={}", call.agentName(),
                            isStreaming, call.taskId() != null ? call.taskId() : "new", setup.contextId, ex);
                    result.completeExceptionally(ex);
                } catch (Error error) {
                    logRemoteError("call", call, isStreaming, setup.contextId, error);
                    throw error;
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
