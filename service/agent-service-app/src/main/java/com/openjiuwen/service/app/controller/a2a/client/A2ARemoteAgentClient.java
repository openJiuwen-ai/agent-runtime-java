/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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

/**
 * A2A remote agent caller using the official SDK {@code
 * Client.builder(card).withTransport(JSONRPCTransport.class, config)} pattern.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient {

    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);
    private static final Map<String, Object> ANSWER_META = Map.of("answer", true);

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
     * Call a remote agent via streaming SendMessage. Streaming chunks (non-answer metadata) are forwarded to
     * {@code streamObserver}; the artifact with {@code metadata.answer=true} is captured as the tool-result text
     * returned via the future.
     *
     * @param agentName registered remote agent name
     * @param message text payload to send
     * @param contextId conversation context ID (shared across calls to same remote)
     * @param streamObserver observer for forwarding streaming chunks to the client
     * @param metadata additional metadata for the call
     * @return future resolving to the final-answer text (tool result for resume)
     */
    public CompletableFuture<String> callStreaming(String agentName, String message, String contextId,
            QueryStreamObserver streamObserver, Map<String, Object> metadata) {
        return callStreaming(agentName, message, contextId, null, streamObserver, metadata);
    }

    /**
     * Parameter object bundling the result of {@link #prepareCall}.
     *
     * @param entry the resolved remote agent entry
     * @param message the built SDK message
     * @param contextId the context/conversation ID
     * @param metadata the metadata map
     */
    private record RemoteCallSetup(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry, Message message, String contextId,
            Map<String, Object> metadata) {
    }

    /**
     * Resolves the remote agent entry and builds the SDK message.
     *
     * @param agentName the remote agent name
     * @param message the text payload
     * @param contextId the context/conversation ID
     * @param taskId the optional task ID for resume
     * @param metadata the metadata map
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
     * Creates or retrieves a cached SDK {@link Client} for the given card and streaming mode.
     *
     * @param card the agent card
     * @param isStreaming whether the client should be in streaming mode
     * @return the SDK client
     */
    private Client createClient(AgentCard card, boolean isStreaming) {
        return clientCache.computeIfAbsent(card.name() + ":" + isStreaming,
                k -> Client.builder(card).clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig()).build());
    }

    /**
     * Completes the future exceptionally with a {@link RemoteInputRequiredException} if not already done.
     *
     * @param future the future to complete
     * @param remoteTaskId the remote task ID
     * @param statusText the status message text
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
     * Call a remote agent via streaming SendMessage. Streaming chunks (non-answer metadata) are forwarded to
     * streamObserver; the artifact with metadata.answer=true is captured as the final result.
     *
     * @param agentName registered remote agent name
     * @param message text payload to send
     * @param contextId conversation context ID
     * @param taskId remote task ID to resume, or null for a new task
     * @param streamObserver observer for forwarding streaming chunks
     * @param metadata additional metadata for the call
     * @return future resolving to the final-answer text
     */
    public CompletableFuture<String> callStreaming(String agentName, String message, String contextId, String taskId,
            QueryStreamObserver streamObserver, Map<String, Object> metadata) {
        var setup = prepareCall(agentName, message, contextId, taskId, metadata);
        log.info("A2A streaming call agent={} taskId={} contextId={} textLen={}", agentName,
                taskId != null ? taskId : "new", setup.contextId, message != null ? message.length() : 0);

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
     * Streaming: forwards non-answer chunks to observer, captures answer artifact.
     *
     * @param aue the task artifact update event
     * @param result the result future to complete with the answer text
     * @param streamObserver the observer for forwarding streaming chunks
     */
    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<String> result,
            QueryStreamObserver streamObserver) {
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) {
            return;
        }
        String text = extractText(a.parts());
        if (text.isEmpty()) {
            return;
        }
        if (a.metadata() != null && Boolean.TRUE.equals(a.metadata().get("answer"))) {
            log.info("Remote answer artifact ({} chars)", text.length());
            if (!result.isDone()) {
                result.complete(text);
            }
        } else {
            streamObserver.onNext(new QueryChunk("chunk", text));
        }
    }

    /**
     * Handles {@link TaskStatusUpdateEvent}: INPUT_REQUIRED or final-without-answer.
     *
     * @param sue the task status update event
     * @param result the result future
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
     * Handles {@link TaskEvent}: fallback when stream ends without explicit answer artifact.
     *
     * @param te the task event
     * @param result the result future
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
     * Call a remote agent via non-streaming SendMessage (synchronous). Blocks until the remote agent completes or
     * requires input.
     *
     * @param agentName registered remote agent name
     * @param message text payload to send
     * @param contextId conversation context ID
     * @param taskId remote task ID to resume, or null for a new task
     * @param metadata additional metadata for the call
     * @return the final-answer text from the remote agent
     * @throws RemoteInputRequiredException if the remote agent requires user input
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
         * @param message the error message
         * @param remoteTaskId the remote task ID
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
         * @param message the error message
         * @param cause the underlying cause
         */
        public RemoteAgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
