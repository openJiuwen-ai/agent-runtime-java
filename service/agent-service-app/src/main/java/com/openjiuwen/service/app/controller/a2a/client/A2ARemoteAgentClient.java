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
import org.a2aproject.sdk.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A2A remote agent caller using the official SDK
 * {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)}
 * pattern.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentClient {

    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentClient.class);
    private static final Map<String, Object> ANSWER_META = Map.of("answer", true);

    private final A2ARemoteAgentCardRegistry registry;

    public A2ARemoteAgentClient(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    /**
     * Call a remote agent via streaming SendMessage. Streaming chunks
     * (non-answer metadata) are forwarded to {@code streamObserver};
     * the artifact with {@code metadata.answer=true} is captured as
     * the tool-result text returned via the future.
     *
     * @param agentName      registered remote agent name
     * @param message        text payload to send
     * @param contextId      conversation context ID (shared across calls to same remote)
     * @param streamObserver observer for forwarding streaming chunks to the client
     * @return future resolving to the final-answer text (tool result for resume)
     */
    public CompletableFuture<String> callStreaming(String agentName, String message,
                                                    String contextId,
                                                    QueryStreamObserver streamObserver,
                                                    Map<String, Object> metadata) {
        return callStreaming(agentName, message, contextId, null, streamObserver, metadata);
    }

    /**
     * Call a remote agent via streaming, optionally resuming an existing task.
     *
     * @param contextId conversation context ID (shared across calls to same remote)
     * @param taskId    remote task ID to resume, or null for a new task
     */
    /** Shared setup: resolve agent entry, build message, create client. */
    private record RemoteCallSetup(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry,
                                    Message message, String contextId,
                                    Map<String, Object> metadata) {}

    private RemoteCallSetup prepareCall(String agentName, String message,
                                         String contextId, String taskId,
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

    private static Client createClient(AgentCard card, boolean streaming) {
        return Client.builder(card)
                .clientConfig(new ClientConfig.Builder().setStreaming(streaming).build())
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .build();
    }

    /** Shared: handle INPUT_REQUIRED status on a CompletableFuture. */
    private static void handleInputRequired(CompletableFuture<String> future,
                                             String remoteTaskId, String statusText) {
        if (future.isDone()) return;
        future.completeExceptionally(new RemoteInputRequiredException(
                statusText.isBlank() ? "Remote agent requires input" : statusText,
                remoteTaskId != null ? remoteTaskId : ""));
    }

    /**
     * Call a remote agent via streaming SendMessage. Streaming chunks
     * (non-answer metadata) are forwarded to streamObserver; the artifact
     * with metadata.answer=true is captured as the final result.
     */
    public CompletableFuture<String> callStreaming(String agentName, String message,
                                                    String contextId, String taskId,
                                                    QueryStreamObserver streamObserver,
                                                    Map<String, Object> metadata) {
        var setup = prepareCall(agentName, message, contextId, taskId, metadata);
        log.info("A2A streaming call agent={} taskId={} contextId={} textLen={}",
                agentName, taskId != null ? taskId : "new", setup.contextId,
                message != null ? message.length() : 0);

        Client client = createClient(setup.entry.card(), true);
        var params = MessageSendParams.builder()
                .message(setup.message)
                .metadata(setup.metadata)
                .build();
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
            if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                    handleArtifact(aue, result, streamObserver);
                } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                    handleStatusUpdate(sue, result);
                }
            } else if (event instanceof TaskEvent te) {
                handleTaskEvent(te, result);
            }
        }), result::completeExceptionally, null);

        result.orTimeout(setup.entry.timeoutSeconds(), TimeUnit.SECONDS);
        return result;
    }

    /** Streaming: forward non-answer chunks to observer, capture answer artifact. */
    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<String> result,
                                 QueryStreamObserver streamObserver) {
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) return;
        String text = extractText(a.parts());
        if (text.isEmpty()) return;
        if (a.metadata() != null && Boolean.TRUE.equals(a.metadata().get("answer"))) {
            log.info("Remote answer artifact ({} chars)", text.length());
            if (!result.isDone()) result.complete(text);
        } else {
            streamObserver.onNext(new QueryChunk("chunk", text));
        }
    }

    /** Shared: handle TaskStatusUpdateEvent (INPUT_REQUIRED or final without answer). */
    private void handleStatusUpdate(TaskStatusUpdateEvent sue, CompletableFuture<String> result) {
        if (sue.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = sue.status().message() != null
                    ? extractText(sue.status().message().parts()) : "";
            log.info("A2A remote INPUT_REQUIRED taskId={} statusText={}", sue.taskId(), statusText);
            handleInputRequired(result, sue.taskId(), statusText);
        } else if (sue.status().state().isFinal() && !result.isDone()) {
            result.complete("");
        }
    }

    /** Shared: handle TaskEvent (fallback when stream ends without explicit answer). */
    private void handleTaskEvent(TaskEvent te, CompletableFuture<String> result) {
        if (result.isDone()) return;
        Task task = te.getTask();
        if (task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = task.status().message() != null
                    ? extractText(task.status().message().parts()) : "";
            log.info("A2A remote INPUT_REQUIRED taskId={} statusText={}", task.id(), statusText);
            handleInputRequired(result, task.id(), statusText);
        } else if (task.status().state().isFinal()) {
            String text = task.artifacts() != null && !task.artifacts().isEmpty()
                    ? extractText(task.artifacts().get(0).parts()) : "";
            log.info("A2A remote result ({} chars)", text.length());
            result.complete(text);
        }
    }

    /**
     * Call a remote agent via non-streaming SendMessage (synchronous).
     * Blocks until the remote agent completes or requires input.
     */
    public String callSync(String agentName, String message, String contextId, String taskId,
                            Map<String, Object> metadata)
            throws RemoteInputRequiredException {
        var setup = prepareCall(agentName, message, contextId, taskId, metadata);
        int timeout = setup.entry.timeoutSeconds();
        log.info("A2A sync call agent={} taskId={} contextId={} textLen={}",
                agentName, taskId != null ? taskId : "new", setup.contextId,
                message != null ? message.length() : 0);

        Client client = createClient(setup.entry.card(), false);
        var params = MessageSendParams.builder()
                .message(setup.message)
                .metadata(setup.metadata)
                .build();
        CompletableFuture<String> result = new CompletableFuture<>();
        client.sendMessage(params,
                List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
                    if (event instanceof TaskEvent te) {
                        handleTaskEvent(te, result);
                    }
                }),
                result::completeExceptionally,
                null);

        try {
            return result.get(timeout, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Remote agent '" + agentName + "' timed out after " + timeout + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for remote agent '" + agentName + "'", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                throw rie;
            }
            throw new RuntimeException("Remote agent '" + agentName + "' failed", e.getCause());
        }
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp) sb.append(tp.text());
        }
        return sb.toString();
    }

    /** Signal that the remote agent requires user input (INPUT_REQUIRED). */
    public static class RemoteInputRequiredException extends RuntimeException {
        private final String remoteTaskId;
        public RemoteInputRequiredException(String message, String remoteTaskId) {
            super(message);
            this.remoteTaskId = remoteTaskId;
        }
        public String getRemoteTaskId() { return remoteTaskId; }
    }
}
