/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
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
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    static final String CALLBACK_URL_METADATA = A2ARemoteCallSupport.CALLBACK_URL_METADATA;

    static final String CALLBACK_TOKEN_METADATA = A2ARemoteCallSupport.CALLBACK_TOKEN_METADATA;

    static final String CALLBACK_ID_METADATA = A2ARemoteCallSupport.CALLBACK_ID_METADATA;

    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);
    private static final int DEFAULT_IO_CONCURRENCY = 16;

    private final A2ARemoteAgentCardRegistry registry;

    private final Map<ClientCacheKey, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService ioExecutor;

    private final A2ARemoteCallSupport remoteCallSupport = new A2ARemoteCallSupport();

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
        return new A2ARemoteCallSupport().buildSendParams(call, contextId);
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
     * @param eventObserver observer for complete remote A2A events
     * @return structured remote outcome
     */
    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver) {
        RemoteAgentEntry entry = registry.get(call.agentName())
                .orElseThrow(() -> new IllegalStateException("Unknown remote agent: " + call.agentName()));
        boolean isStreaming = entry.isStreaming() && call.isCallerStreaming();
        return callOutcome(call, eventObserver, isStreaming);
    }

    private CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver,
            boolean isStreaming) {
        var setup = prepareCall(call);
        log.info("A2A call agent={} streaming={} taskId={} contextId={} textLen={}", call.agentName(), isStreaming,
                call.taskId() != null ? call.taskId() : "new", setup.contextId,
                call.message() != null ? call.message().length() : 0);

        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        boolean isCallbackMode = remoteCallSupport.isCallbackMode(setup.params);
        BiConsumer<ClientEvent, AgentCard> eventConsumer = (event, ignoredCard) -> {
            try {
                handleClientEvent(event, result, eventObserver, isCallbackMode, isStreaming);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        };
        Client client = createClient(setup.entry, isStreaming);
        AtomicReference<Future<?>> invocationTask = new AtomicReference<>();
        try {
            Future<?> submitted = ioExecutor.submit(() -> {
                try {
                    withApplicationClassLoader(() -> {
                        client.sendMessage(setup.params, List.of(eventConsumer),
                                error -> remoteCallSupport.completeOnStreamEnd(call.agentName(), result, error), null);
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

    private void handleClientEvent(ClientEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isCallbackMode, boolean isStreaming) {
        remoteCallSupport.accept(event, result, eventObserver, isCallbackMode, isStreaming);
    }

    private static boolean completeOutcomeOnStreamEnd(String agentName, CompletableFuture<?> result, Throwable error) {
        return new A2ARemoteCallSupport().completeOnStreamEnd(agentName, result, error);
    }

    private void handleOutcomeStatus(TaskStatusUpdateEvent event, Task task,
            CompletableFuture<RemoteCallOutcome> result, RemoteAgentCaller.EventObserver eventObserver,
            boolean isCallbackMode) {
        remoteCallSupport.accept(new TaskUpdateEvent(task, event), result, eventObserver, isCallbackMode, true);
    }

    private void handleOutcomeTask(TaskEvent event, CompletableFuture<RemoteCallOutcome> result,
            RemoteAgentCaller.EventObserver eventObserver, boolean isCallbackMode, boolean isStreaming) {
        remoteCallSupport.accept(event, result, eventObserver, isCallbackMode, isStreaming);
    }

    private void handleOutcomeMessage(MessageEvent event, CompletableFuture<RemoteCallOutcome> result) {
        if (!result.isDone()) {
            remoteCallSupport.mapMessage(event.getMessage()).ifPresent(result::complete);
        }
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
        return A2ARemoteCallSupport.resultCategory(state);
    }

}
