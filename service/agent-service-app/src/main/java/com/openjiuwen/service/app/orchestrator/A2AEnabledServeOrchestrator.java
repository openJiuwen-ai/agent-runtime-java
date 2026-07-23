/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.google.gson.Gson;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentAnswerExtractor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.app.controller.a2a.client.RemoteInputRequiredException;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A-aware orchestrator with interrupt-resume chain.
 *
 * <p>
 * Detects {@code "interrupt"} chunks from the agent handler, routes
 * {@code a2a_delegate} interrupts to a remote agent via
 * {@link RemoteAgentCaller}, and resumes the local agent with the remote
 * result. Other interrupts are forwarded as {@code INPUT_REQUIRED}.
 *
 * @since 0.1.0
 */
public class A2AEnabledServeOrchestrator implements ServeOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(A2AEnabledServeOrchestrator.class);

    private static final Gson GSON = new Gson();

    /**
     * No-op stream observer used as a sentinel in sync/query mode.
     */
    private static final QueryStreamObserver NOOP_OBSERVER = new QueryStreamObserver() {
        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /**
     * Prefix for orchestrator-owned shadow task ids, keeping them out of the real
     * A2A task id space.
     */
    private static final String SHADOW_KEY_PREFIX = "shadow:";

    /**
     * Capturing observer that wraps the optional client passthrough while a
     * remote agent call is in flight.
     *
     * <p>It records the final answer envelope, any control {@code interrupt}
     * chunk, and any terminal error. It also tracks whether the passthrough
     * (client) observer has already received a terminal signal ({@code onComplete}
     * or {@code onError}) so that callers can honour the observer contract:
     * once {@code onError} fires, no further notifications (not even
     * {@code onComplete}) must reach the client.
     *
     * <p>When the SPI runs in sync mode ({@link RemoteAgentCall#streaming()}
     * is {@code false}), the answer arrives as a plain-text
     * {@code QueryChunk("chunk", answer)} (the raw {@code task.artifacts()} text)
     * rather than a JSON answer envelope. In that case {@link #capturedAnswer()}
     * falls back to {@link #lastRawText} so the legacy {@code callSync} answer
     * contract is preserved.
     */
    private static final class RemoteCallCaptureObserver implements QueryStreamObserver {
        private final QueryStreamObserver passthrough;

        private final AtomicReference<String> captured = new AtomicReference<>();

        private final AtomicReference<Map<?, ?>> capturedEnvelope = new AtomicReference<>();

        private final AtomicReference<String> lastRawText = new AtomicReference<>();

        private final AtomicReference<QueryChunk> pendingInterrupt = new AtomicReference<>();

        private final AtomicReference<Throwable> error = new AtomicReference<>();

        private final AtomicBoolean terminallyNotified = new AtomicBoolean(false);

        RemoteCallCaptureObserver(QueryStreamObserver passthrough) {
            this.passthrough = passthrough;
        }

        @Override
        public void onNext(QueryChunk chunk) {
            if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                // Interrupt is a control signal: capture it for the caller to
                // translate into a RemoteInputRequiredException, but do not
                // forward to the passthrough — the upper layer (e.g.
                // refreshPendingOnRemoteInput / handleRemoteInputRequired) is
                // responsible for notifying the client with a properly framed
                // interrupt chunk. This avoids double-delivery when the caller
                // already emitted the interrupt via the stream.
                pendingInterrupt.set(chunk);
                return;
            }
            if (passthrough != null && !passthrough.isCancelled()) {
                passthrough.onNext(chunk);
            }
            if (QueryChunk.TYPE_CHUNK.equals(chunk.getType())) {
                Object data = chunk.getData();
                if (data instanceof String raw) {
                    lastRawText.set(raw);
                    RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(captured::set);
                    RemoteAgentAnswerExtractor.extractAnswerEnvelope(raw)
                            .ifPresent(capturedEnvelope::set);
                } else if (data instanceof Map<?, ?> m
                        && RemoteAgentAnswerExtractor.ANSWER_ENVELOPE_TYPE.equals(m.get("type"))) {
                    // Some Caller implementations emit the answer envelope as a
                    // Map chunk rather than a JSON string; extract the business
                    // text directly so sync query() mode still captures it.
                    RemoteAgentAnswerExtractor.extractAnswerFromMap(m).ifPresent(captured::set);
                    capturedEnvelope.set(m);
                }
            }
        }

        @Override
        public void onComplete() {
            // If the remote signalled an interrupt, the upper layer will
            // handle client notification (including completion); suppress
            // passthrough completion to avoid double onComplete.
            if (pendingInterrupt.get() != null) {
                return;
            }
            if (passthrough != null && !passthrough.isCancelled()) {
                passthrough.onComplete();
                terminallyNotified.set(true);
            }
        }

        @Override
        public void onError(Throwable cause) {
            error.set(cause);
            if (passthrough != null && !passthrough.isCancelled()) {
                passthrough.onError(cause);
                terminallyNotified.set(true);
            }
        }

        @Override
        public boolean isCancelled() {
            return passthrough != null && passthrough.isCancelled();
        }

        String capturedAnswer() {
            String envelopeAnswer = captured.get();
            if (envelopeAnswer != null) {
                return envelopeAnswer;
            }
            // Sync-mode fallback: the SPI emits the raw task.artifacts() text as
            // a plain chunk; fall back to it when no answer envelope was seen.
            return lastRawText.get();
        }

        Map<?, ?> capturedEnvelope() {
            return capturedEnvelope.get();
        }

        QueryChunk pendingInterrupt() {
            return pendingInterrupt.get();
        }

        Throwable error() {
            return error.get();
        }

        boolean isTerminallyNotified() {
            return terminallyNotified.get();
        }
    }

    /**
     * Outcome of a remote agent call: the captured answer text (if any), the
     * control interrupt chunk (if the remote signalled INPUT_REQUIRED), and any
     * terminal error. {@code terminallyNotified} records whether the passthrough
     * observer already received a terminal signal ({@code onComplete} or
     * {@code onError}); callers MUST NOT invoke {@code observer.onComplete()}
     * when this is {@code true}, to honour the observer contract.
     */
    private record RemoteCallResult(String answer, QueryChunk interrupt, Throwable error,
                                    boolean terminallyNotified, Map<?, ?> envelope) {
        boolean hasInterrupt() {
            return interrupt != null;
        }

        boolean hasError() {
            return error != null;
        }

        /**
         * Translates the captured interrupt chunk into a
         * {@link RemoteInputRequiredException}. Defensive: if the interrupt data
         * does not carry a {@code remote_task_id}, still surface
         * INPUT_REQUIRED (with an empty remote task id) rather than silently
         * dropping the signal.
         */
        RemoteInputRequiredException toInputRequiredException() {
            Object data = interrupt.getData();
            if (data instanceof Map<?, ?> m && m.get("remote_task_id") instanceof String rtid) {
                String message = m.get("message") instanceof String s ? s : "Remote agent requires input";
                return new RemoteInputRequiredException(message, rtid);
            }
            return new RemoteInputRequiredException("Remote agent requires input", "");
        }
    }

    /**
     * Invokes the remote agent caller and captures the outcome, while forwarding
     * chunks to the optional passthrough observer. Returns a
     * {@link RemoteCallResult} describing the outcome; callers translate
     * {@link RemoteCallResult#hasInterrupt()} into a
     * {@link RemoteInputRequiredException} and decide whether to call
     * {@code observer.onComplete()} based on
     * {@link RemoteCallResult#terminallyNotified()}.
     *
     * <p>In sync mode ({@link RemoteAgentCall#streaming()} is {@code false}), the
     * SPI preserves the legacy {@code callSync} contract and throws
     * {@link RemoteInputRequiredException} / {@link RemoteAgentException}
     * directly. This method catches those exceptions and translates them into a
     * {@link RemoteCallResult} so callers can handle both modes uniformly.
     *
     * @param call
     *            the remote call coordinates
     * @param passthrough
     *            optional observer to receive streaming chunks; {@code null} for sync mode
     * @return the remote call outcome (answer text, interrupt, error, terminal-notification flag)
     */
    private RemoteCallResult callRemoteAndCapture(RemoteAgentCall call, QueryStreamObserver passthrough) {
        RemoteCallCaptureObserver wrapper = new RemoteCallCaptureObserver(passthrough);
        try {
            remoteAgentCaller.call(call, wrapper);
        } catch (RemoteInputRequiredException rie) {
            // Sync mode: SPI threw INPUT_REQUIRED (preserves legacy callSync).
            // Wrap it as a pending interrupt so the caller can translate it via
            // RemoteCallResult.toInputRequiredException(). Any answer captured
            // before the interrupt is preserved.
            return new RemoteCallResult(wrapper.capturedAnswer() != null ? wrapper.capturedAnswer() : "",
                    new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                            "message", rie.getMessage() != null ? rie.getMessage() : "Remote agent requires input",
                            "remote_task_id", rie.getRemoteTaskId() != null ? rie.getRemoteTaskId() : "")),
                    null, wrapper.isTerminallyNotified(), wrapper.capturedEnvelope());
        } catch (RemoteAgentException rae) {
            // Sync mode: SPI threw a remote failure (preserves legacy callSync).
            return new RemoteCallResult(wrapper.capturedAnswer() != null ? wrapper.capturedAnswer() : "",
                    null, rae, wrapper.isTerminallyNotified(), wrapper.capturedEnvelope());
        }
        String answer = wrapper.capturedAnswer();
        return new RemoteCallResult(answer != null ? answer : "", wrapper.pendingInterrupt(), wrapper.error(),
            wrapper.isTerminallyNotified(), wrapper.capturedEnvelope());
    }

    private final AgentHandler agentHandler;

    private final TaskStore taskStore;

    private final RemoteAgentCaller remoteAgentCaller;

    private final RemoteAgentCardResolver cardResolver;

    private final ActiveStreamRegistry streamRegistry;

    private final String agentId;

    /**
     * Constructs the orchestrator with required dependencies.
     *
     * @param agentHandler
     *            the local agent handler
     * @param taskStore
     *            the A2A task store for shadow tasks
     * @param remoteAgentCaller
     *            the remote agent caller SPI
     * @param cardResolver
     *            the remote agent card resolver SPI
     * @param streamRegistry
     *            the active stream registry for cancellation
     * @param agentId
     *            this agent's identity for shadow task key namespacing
     */
    public A2AEnabledServeOrchestrator(AgentHandler agentHandler, TaskStore taskStore,
        RemoteAgentCaller remoteAgentCaller, RemoteAgentCardResolver cardResolver, ActiveStreamRegistry streamRegistry,
        String agentId) {
        this.agentHandler = agentHandler;
        this.taskStore = taskStore;
        this.remoteAgentCaller = remoteAgentCaller;
        this.cardResolver = cardResolver;
        this.streamRegistry = streamRegistry;
        this.agentId = agentId == null || agentId.isBlank() ? "agent" : agentId;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Orchestrator query START conversationId={}", request.getConversationId());
        ServeRequest current = request;
        while (true) {
            QueryResumeResult resumeResult = syncResumePending(current);
            if (resumeResult.response() != null) {
                return resumeResult.response();
            }
            if (resumeResult.request().isEmpty()) {
                return buildInterruptQueryResponse(request.getConversationId());
            }
            current = resumeResult.request().get();

            QueryResponse response = agentHandler.query(current);
            Map<String, Object> interruptData = extractInterruptFromResponse(response);
            if (interruptData.isEmpty()) {
                Optional<QueryResponse> forwarded = forwardIfThreeFieldResult(response, current, null);
                return forwarded.orElse(response);
            }

            Optional<ServeRequest> interruptResult = handleQueryInterrupt(interruptData, current, response);
            if (interruptResult.isEmpty()) {
                return response;
            }
            current = interruptResult.get();
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        log.info("Orchestrator streamQuery START conversationId={}", request.getConversationId());
        var handle = streamRegistry.register(request.getConversationId());
        try {
            ServeRequest current = request;
            while (!handle.isCancelled() && !observer.isCancelled()) {
                Optional<ServeRequest> opt = tryResumePending(current, observer, handle);
                if (opt.isEmpty()) {
                    return;
                }
                current = opt.get();

                AgentRunResult agentResult = runAgentAndCaptureInterruptOrThreeField(current, observer, handle);
                if (agentResult.threeFieldEnvelope() != null) {
                    Optional<RemoteAgentCall> forward = buildForwardCall(agentResult.threeFieldEnvelope(), current, true);
                    if (forward.isPresent()) {
                        log.info("Orchestrator forwarding three-field chunk to remote agent={} convId={}",
                            forward.get().agentId(), current.getConversationId());
                        RemoteCallResult remoteResult = callRemoteAndCapture(forward.get(), observer);
                        // The capturing wrapper suppresses passthrough terminal
                        // signals when the remote returns INPUT_REQUIRED (and
                        // does not call onError for the interrupt path). Surface
                        // the interrupt to the client and terminate the stream
                        // so the client observer is never left hanging.
                        if (remoteResult.hasInterrupt()) {
                            RemoteInputRequiredException rie = remoteResult.toInputRequiredException();
                            if (!observer.isCancelled()) {
                                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                                        "message", rie.getMessage(),
                                        "remote_task_id", rie.getRemoteTaskId())));
                                observer.onComplete();
                            }
                        } else if (remoteResult.hasError() && !remoteResult.terminallyNotified()
                                && !observer.isCancelled()) {
                            // Defensive: wrapper did not forward onError for some
                            // reason — terminate the stream so the client is not
                            // left waiting.
                            observer.onComplete();
                        }
                    } else {
                        observer.onComplete();
                    }
                    return;
                }
                QueryChunk interrupt = agentResult.interrupt();
                if (interrupt == null) {
                    return;
                }

                Optional<ServeRequest> interruptResult = handleInterrupt(interrupt, current, observer);
                if (interruptResult.isEmpty()) {
                    return;
                }
                current = interruptResult.get();
            }
        } finally {
            streamRegistry.unregister(request.getConversationId(), handle);
        }
    }

    /**
     * If a pending remote task exists, resume it.
     *
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @param handle
     *            the stream cancellation handle
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> tryResumePending(ServeRequest current, QueryStreamObserver observer,
            StreamCancellationHandle handle) {
        List<Task> pending = findPending(current.getConversationId());
        if (pending.isEmpty()) {
            return Optional.of(current);
        }

        Task pt = pending.get(0);
        String agentName = metadataString(pt, "_agent_name");
        String remoteTaskId = metadataString(pt, "_remote_task_id");
        String streamMode = metadataString(pt, "_stream_mode");
        boolean isSse = InterruptData.STREAM_MODE_SSE.equals(streamMode);
        log.info("Orchestrator resuming pending task convId={} agent={} remoteTaskId={} streamMode={}",
                current.getConversationId(), agentName, remoteTaskId, streamMode);
        RemoteCallResult result;
        try {
            // Only pass the observer (stream the remote content to the client) when the
            // delegation opted into
            // SSE passthrough; otherwise resolve synchronously so the remote result reaches
            // the tool only.
            result = callRemoteAndCapture(new RemoteAgentCall(
                    agentName, current, null, current.getConversationId(), remoteTaskId,
                    current.lastUserQuery(), isSse), isSse ? observer : null);
        } catch (Exception e) {
            // RemoteAgentCall construction (e.g. blank agent name) can throw before
            // the remote is contacted; no observer notification has been issued yet.
            log.error("Remote call '{}' failed for pending task", agentName, e);
            return failRemoteStream(current, agentName, observer, e, false);
        }
        if (result.hasInterrupt()) {
            return refreshPendingOnRemoteInput(current, pt, result.toInputRequiredException(), observer);
        }
        if (result.hasError()) {
            Throwable failure = result.error();
            if (isRecoverableRemoteFailure(failure)) {
                return resumePendingAfterRemoteFailure(current, pt, agentName, failure);
            }
            return failRemoteStream(current, agentName, observer, failure, result.terminallyNotified());
        }
        deleteShadowTask(pt.id());
        return Optional.of(buildResumeRequest(current, result.answer(), "", ""));
    }

    private Optional<ServeRequest> resumePendingAfterRemoteFailure(ServeRequest current, Task pending, String agentName,
            Throwable failure) {
        log.warn("Remote call '{}' failed for pending task; resuming parent with code={}", agentName,
                remoteFailure(failure).map(RemoteAgentException::getCode)
                        .orElse(RemoteAgentException.CODE_REMOTE_ERROR));
        log.debug("Remote pending task failure", failure);
        deleteShadowTask(pending.id());
        return Optional.of(buildResumeRequest(current, remoteFailureContent(failure), "", ""));
    }

    /**
     * Handles a remote INPUT_REQUIRED hit while resuming a pending task: refreshes
     * the shadow task with the new remote task id (so the next resume targets the
     * right remote task) while preserving the stream mode, then forwards the
     * interrupt to the client. Agent name and stream mode are read from the
     * existing task metadata.
     *
     * @param current
     *            the current serve request
     * @param pt
     *            the existing pending shadow task
     * @param rie
     *            the remote input-required signal carrying the new remote task id
     * @param observer
     *            the query stream observer
     * @return {@link Optional#empty()} to stop the loop with the shadow task
     *         preserved
     */
    private Optional<ServeRequest> refreshPendingOnRemoteInput(ServeRequest current, Task pt,
            RemoteInputRequiredException rie, QueryStreamObserver observer) {
        saveShadowTask(current.getConversationId(), metadataString(pt, "_agent_name"),
                metadataString(pt, "_remote_url"), remoteTaskIdOrExisting(rie, pt), metadataString(pt, "_stream_mode"));
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", rie.getMessage())));
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Outcome of running the local agent: either an interrupt chunk (for the
     * classic {@code a2a_delegate}/{@code ask_user} path) or a three-field
     * answer envelope emitted inline as a chunk (Versatile intent workflow
     * short-circuit). At most one of the two is non-null.
     */
    private record AgentRunResult(QueryChunk interrupt, Map<String, Object> threeFieldEnvelope) {
    }

    /**
     * Runs the local agent and captures either an interrupt chunk (for the
     * classic {@code a2a_delegate}/{@code ask_user} path) or a three-field
     * answer envelope emitted inline as a chunk (Versatile intent workflow
     * short-circuit). At most one of the two is non-null.
     *
     * <p>When a chunk carries an {@code answer} envelope with a non-blank
     * {@code agent_id}, it is captured for downstream forwarding instead of
     * being forwarded to the client observer. Interrupts are captured for the
     * caller to translate into a {@link RemoteInputRequiredException}; normal
     * chunks are forwarded to {@code observer}. The wrapper suppresses
     * {@code observer.onComplete()} when an interrupt or envelope was captured
     * so that the caller retains sole responsibility for terminal signalling.
     *
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @param handle
     *            the stream cancellation handle
     * @return the run outcome (interrupt chunk and/or three-field envelope)
     */
    private AgentRunResult runAgentAndCaptureInterruptOrThreeField(ServeRequest current, QueryStreamObserver observer,
        StreamCancellationHandle handle) {
        AtomicReference<QueryChunk> interruptHolder = new AtomicReference<>();
        AtomicReference<Map<String, Object>> envelopeHolder = new AtomicReference<>();
        agentHandler.streamQuery(current, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                    interruptHolder.set(chunk);
                    return;
                }
                if (chunk.getData() instanceof Map<?, ?> m
                        && RemoteAgentAnswerExtractor.ANSWER_ENVELOPE_TYPE.equals(m.get("type"))
                        && m.get("agent_id") instanceof String aid && !aid.isBlank()) {
                    Map<String, Object> envelope = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : m.entrySet()) {
                        envelope.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    envelopeHolder.set(envelope);
                    return;
                }
                observer.onNext(chunk);
            }

            @Override
            public void onComplete() {
                if (interruptHolder.get() == null && envelopeHolder.get() == null) {
                    observer.onComplete();
                }
            }

            @Override
            public void onError(Throwable e) {
                log.error("Agent stream error", e);
                observer.onError(e);
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled() || observer.isCancelled();
            }
        });
        return new AgentRunResult(interruptHolder.get(), envelopeHolder.get());
    }

    /**
     * Inspects a {@link QueryResponse} for a Versatile intent workflow
     * three-field result ({@code agent_id} + {@code response_content} + optional
     * {@code intent_id}) and, when present and addressed to a different agent,
     * forwards the call to the remote agent via {@link RemoteAgentCaller}.
     *
     * <p>In sync {@code query()} mode ({@code observer == null}), the remote's
     * captured answer text is wrapped into a new {@link QueryResponse} with
     * {@code role=assistant} + {@code response_content} fields. In streaming
     * mode, chunks are forwarded directly to the observer via
     * {@link #callRemoteAndCapture(RemoteAgentCall, QueryStreamObserver)}; the
     * caller is responsible for any post-call observer notification (the
     * capturing wrapper honours the observer contract for terminal signals).
     *
     * @param response
     *            the local handler's response to inspect
     * @param current
     *            the current serve request
     * @param observer
     *            the client stream observer, or {@code null} for sync mode
     * @return the forwarded response, or {@link Optional#empty()} if no
     *         forwarding applies (caller should return the original response)
     */
    private Optional<QueryResponse> forwardIfThreeFieldResult(QueryResponse response, ServeRequest current,
        QueryStreamObserver observer) {
        if (!(response.getResult() instanceof Map<?, ?> resultMap)) {
            return Optional.empty();
        }
        Optional<RemoteAgentCall> callOpt = buildForwardCall(resultMap, current, observer != null);
        if (callOpt.isEmpty()) {
            return Optional.empty();
        }
        RemoteAgentCall call = callOpt.get();

        log.info("Orchestrator forwarding three-field result to remote agent={} convId={}",
            call.agentId(), current.getConversationId());

        if (observer == null) {
            // Sync query mode: NOOP observer, translate captured answer into a
            // fresh QueryResponse. On interrupt or error, fall back to the local
            // response rather than crashing.
            RemoteCallResult remoteResult = callRemoteAndCapture(call, NOOP_OBSERVER);
            if (remoteResult.hasInterrupt() || remoteResult.hasError()) {
                log.warn("Orchestrator three-field forward to agent={} convId={} did not complete cleanly "
                    + "(interrupt={}, error={}); returning local response", call.agentId(), current.getConversationId(),
                    remoteResult.hasInterrupt(), remoteResult.hasError() ? remoteResult.error().getMessage() : "n/a");
                return Optional.empty();
            }
            String remoteContent = remoteResult.answer();
            Map<String, Object> forwardedResult = new LinkedHashMap<>();
            forwardedResult.put("role", "assistant");
            forwardedResult.put("content", remoteContent);
            forwardedResult.put("response_content", remoteContent);
            Map<?, ?> envelope = remoteResult.envelope();
            if (envelope != null) {
                Object remoteAgentId = envelope.get("agent_id");
                if (remoteAgentId != null) {
                    forwardedResult.put("agent_id", remoteAgentId);
                }
                Object remoteIntentId = envelope.get("intent_id");
                if (remoteIntentId != null) {
                    forwardedResult.put("intent_id", remoteIntentId);
                }
            }
            return Optional.of(new QueryResponse(forwardedResult, current.getConversationId()));
        }

        // Streaming mode: chunks have already been forwarded to the observer by
        // the capturing wrapper (including any terminal signal). Nothing more
        // to do here — the streamQuery loop returns immediately after this call.
        callRemoteAndCapture(call, observer);
        return Optional.of(response);
    }

    /**
     * Builds a {@link RemoteAgentCall} from a three-field envelope, or returns
     * {@code Optional.empty()} if the envelope should not be forwarded (missing
     * or blank {@code agent_id}).
     *
     * <p>Self-forward (envelope {@code agent_id} equal to this orchestrator's
     * own id) is intentionally NOT skipped here. Re-classification per PRD §4.6
     * requires the downstream business runtime to forward the three-field
     * result back to the fixed layer-1 agent, creating a new Task on the
     * layer-1 runtime — even when that target equals the current orchestrator's
     * own agent identity in edge configurations. Loop protection (deadline,
     * max-jump count, repeated-path detection) is the responsibility of the
     * runtime downstream-call capability per L2 §2.2, not this orchestrator.
     * Silently skipping self-forward would also mask configuration errors as
     * successful completion, violating PRD §9.1 structured-failure requirement.
     *
     * @param envelope  the three-field envelope ({@code agent_id} +
     *                  {@code response_content} + optional {@code intent_id})
     * @param current   the current serve request
     * @param streaming whether to use streaming SDK client + observer-routed errors
     * @return the remote call coordinates, or empty if forwarding should be skipped
     */
    private Optional<RemoteAgentCall> buildForwardCall(Map<?, ?> envelope, ServeRequest current, boolean streaming) {
        Object agentIdObj = envelope.get("agent_id");
        if (!(agentIdObj instanceof String aid) || aid.isBlank()) {
            return Optional.empty();
        }
        if (aid.equals(this.agentId)) {
            log.warn("Orchestrator forwarding three-field result to self agentId={} convId={} "
                + "(re-classification or config error; loop protection delegated to downstream-call capability)",
                aid, current.getConversationId());
        }
        Object rc = envelope.get("response_content");
        String rcStr = rc instanceof String s ? s : null;
        return Optional.of(new RemoteAgentCall(aid, current, rcStr, current.getConversationId(),
                null, current.lastUserQuery(), streaming));
    }

    /**
     * Routes an interrupt chunk.
     *
     * @param interrupt
     *            the interrupt chunk
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleInterrupt(QueryChunk interrupt, ServeRequest current,
            QueryStreamObserver observer) {
        var data = resolveInterruptData(interrupt);
        log.info("Orchestrator interrupt kind={} agentName={} toolName={} convId={}", data.kind(), data.agentName(),
                data.toolName(), current.getConversationId());
        if (InterruptData.KIND_A2A_DELEGATE.equals(data.kind())) {
            return handleA2ADelegate(data, current, observer);
        }
        log.info("Orchestrator forwarding ask_user interrupt to client convId={}", current.getConversationId());
        observer.onNext(interrupt);
        observer.onComplete();
        return Optional.empty();
    }

    /**
     * Delegates to remote agent: chooses SSE (streaming) or sync (blocking) path.
     *
     * @param data
     *            the interrupt data
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleA2ADelegate(InterruptData data, ServeRequest current,
            QueryStreamObserver observer) {
        if (InterruptData.STREAM_MODE_SSE.equals(data.streamMode())) {
            return delegateSse(data, current, observer);
        }
        return delegateSync(data, current, observer);
    }

    /**
     * SSE: streaming call — intermediate output forwards to observer.
     *
     * @param data
     *            the interrupt data
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> delegateSse(InterruptData data, ServeRequest current, QueryStreamObserver observer) {
        log.info("Orchestrator delegating (sse) to remote agent={} convId={}", data.agentName(),
                current.getConversationId());
        RemoteCallResult result;
        try {
            result = callRemoteAndCapture(new RemoteAgentCall(
                    data.agentName(), current, null, current.getConversationId(), null,
                    data.message(), true), observer);
        } catch (Exception e) {
            log.error("Remote call '{}' failed (sse)", data.agentName(), e);
            return failRemoteStream(current, data.agentName(), observer, e, false);
        }
        if (result.hasInterrupt()) {
            return handleRemoteInputRequired(data, current, observer, result.toInputRequiredException());
        }
        if (result.hasError()) {
            Throwable failure = result.error();
            if (isRecoverableRemoteFailure(failure)) {
                return resumeAfterRemoteFailure(data, current, failure);
            }
            return failRemoteStream(current, data.agentName(), observer, failure, result.terminallyNotified());
        }
        log.info("Orchestrator remote result received ({} chars), building resume", result.answer().length());
        return Optional.of(buildResumeRequest(current, result.answer(), data.toolCallId(), data.toolName()));
    }

    /**
     * Sync: blocking call — only final result or interrupt returned.
     *
     * @param data
     *            the interrupt data
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> delegateSync(InterruptData data, ServeRequest current,
            QueryStreamObserver observer) {
        log.info("Orchestrator delegating (sync) to remote agent={} convId={}", data.agentName(),
                current.getConversationId());
        RemoteCallResult result;
        try {
            result = callRemoteAndCapture(new RemoteAgentCall(
                    data.agentName(), current, null, current.getConversationId(), null,
                    data.message(), false), null);
        } catch (Exception e) {
            log.error("Remote call '{}' failed (sync)", data.agentName(), e);
            return failRemoteStream(current, data.agentName(), observer, e, false);
        }
        if (result.hasInterrupt()) {
            return handleRemoteInputRequired(data, current, observer, result.toInputRequiredException());
        }
        if (result.hasError()) {
            Throwable failure = result.error();
            if (isRecoverableRemoteFailure(failure)) {
                return resumeAfterRemoteFailure(data, current, failure);
            }
            return failRemoteStream(current, data.agentName(), observer, failure, result.terminallyNotified());
        }
        log.info("Orchestrator remote result received ({} chars), building resume", result.answer().length());
        return Optional.of(buildResumeRequest(current, result.answer(), data.toolCallId(), data.toolName()));
    }

    private Optional<ServeRequest> resumeAfterRemoteFailure(InterruptData data, ServeRequest current,
            Throwable failure) {
        log.warn("Remote call '{}' failed; resuming parent with code={}", data.agentName(), remoteFailure(failure)
                .map(RemoteAgentException::getCode).orElse(RemoteAgentException.CODE_REMOTE_ERROR));
        log.debug("Remote delegation failure", failure);
        return Optional
                .of(buildResumeRequest(current, remoteFailureContent(failure), data.toolCallId(), data.toolName()));
    }

    /**
     * Handles remote INPUT_REQUIRED: saves shadow task, notifies client, and stops
     * the loop.
     *
     * @param data
     *            the interrupt data
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @param rie
     *            the remote input required exception
     * @return {@link Optional#empty()} always, indicating the loop should stop
     */
    private Optional<ServeRequest> handleRemoteInputRequired(InterruptData data, ServeRequest current,
            QueryStreamObserver observer, RemoteInputRequiredException rie) {
        log.info("Orchestrator remote INPUT_REQUIRED convId={} remoteTaskId={}", current.getConversationId(),
                rie.getRemoteTaskId());
        saveShadowTask(current.getConversationId(), data.agentName(), cardResolver.resolveJsonRpcUrl(data.agentName()),
                rie.getRemoteTaskId(), data.streamMode());
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", rie.getMessage())));
        observer.onComplete();
        return Optional.empty();
    }

    @Override
    public void cancelActive(String conversationId) {
        streamRegistry.cancel(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        cancelActive(conversationId);
        agentHandler.clearSession(conversationId);
        var result = taskStore.list(ListTasksParams.builder().contextId(conversationId).build());
        for (Task t : result.tasks()) {
            deleteShadowTask(t.id());
        }
        log.info("Reset {}: {} A2A tasks cleaned", conversationId, result.tasks().size());
    }

    /**
     * Structured interrupt data decoded from a {@code QueryChunk("interrupt")}.
     */
    private record InterruptData(String kind, String agentName, String message, String toolCallId, String toolName,
            String streamMode) {
        static final String KIND_ASK_USER = "ask_user";

        static final String KIND_A2A_DELEGATE = "a2a_delegate";

        static final InterruptData EMPTY = new InterruptData(KIND_ASK_USER, "", "", "", "", "");

        static final String STREAM_MODE_SSE = "sse";
    }

    private record QueryResumeResult(Optional<ServeRequest> request, QueryResponse response) {
        static QueryResumeResult continueWith(ServeRequest request) {
            return new QueryResumeResult(Optional.of(request), null);
        }

        static QueryResumeResult respond(QueryResponse response) {
            return new QueryResumeResult(Optional.empty(), response);
        }
    }

    /**
     * Sync variant of {@link #tryResumePending} for non-streaming query mode.
     *
     * @param current
     *            the current serve request
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private QueryResumeResult syncResumePending(ServeRequest current) {
        List<Task> pending = findPending(current.getConversationId());
        if (pending.isEmpty()) {
            return QueryResumeResult.continueWith(current);
        }

        Task pt = pending.get(0);
        String agentName = metadataString(pt, "_agent_name");
        String remoteTaskId = metadataString(pt, "_remote_task_id");
        String streamMode = metadataString(pt, "_stream_mode");
        log.info("Orchestrator syncResumePending convId={} agent={} remoteTaskId={} streamMode={}",
                current.getConversationId(), agentName, remoteTaskId, streamMode);
        RemoteCallResult result;
        try {
            result = callRemoteAndCapture(new RemoteAgentCall(
                    agentName, current, null, current.getConversationId(), remoteTaskId,
                    current.lastUserQuery(), false), null);
        } catch (Exception e) {
            log.error("Remote call '{}' failed for pending task", agentName, e);
            throw failRemoteQuery(current, agentName, e);
        }
        if (result.hasInterrupt()) {
            return pendingRemoteInputRequiredResponse(current, pt, agentName, streamMode,
                result.toInputRequiredException());
        }
        if (result.hasError()) {
            Throwable failure = result.error();
            if (isRecoverableRemoteFailure(failure)) {
                return resumePendingQueryAfterRemoteFailure(current, pt, agentName, failure);
            }
            throw failRemoteQuery(current, agentName, failure);
        }
        deleteShadowTask(pt.id());
        return QueryResumeResult.continueWith(buildResumeRequest(current, result.answer(), "", ""));
    }

    private QueryResumeResult resumePendingQueryAfterRemoteFailure(ServeRequest current, Task pending, String agentName,
            Throwable failure) {
        log.warn("Remote call '{}' failed for pending task; resuming parent with code={}", agentName,
                remoteFailure(failure).map(RemoteAgentException::getCode)
                        .orElse(RemoteAgentException.CODE_REMOTE_ERROR));
        log.debug("Remote pending query failure", failure);
        deleteShadowTask(pending.id());
        return QueryResumeResult.continueWith(buildResumeRequest(current, remoteFailureContent(failure), "", ""));
    }

    /**
     * Handles a2a_delegate interrupt in query mode.
     *
     * @param interruptData
     *            the interrupt data map
     * @param current
     *            the current serve request
     * @param response
     *            the query response
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleQueryInterrupt(Map<String, Object> interruptData, ServeRequest current,
            QueryResponse response) {
        var data = resolveInterruptDataFromMap(interruptData);
        log.info("Orchestrator query interrupt kind={} agentName={} convId={}", data.kind(), data.agentName(),
                current.getConversationId());
        if (InterruptData.KIND_A2A_DELEGATE.equals(data.kind())) {
            log.info("Orchestrator query delegating ({}) to remote agent={} convId={}",
                    InterruptData.STREAM_MODE_SSE.equals(data.streamMode()) ? "sse" : "sync", data.agentName(),
                    current.getConversationId());
            RemoteCallResult result;
            try {
                result = callRemoteAndCapture(new RemoteAgentCall(
                        data.agentName(), current, null, current.getConversationId(), null,
                        data.message(), false), null);
            } catch (Exception e) {
                log.error("Remote call '{}' failed", data.agentName(), e);
                throw failRemoteQuery(current, data.agentName(), e);
            }
            if (result.hasInterrupt()) {
                return remoteInputRequiredResponse(interruptData, response, current, data,
                    result.toInputRequiredException());
            }
            if (result.hasError()) {
                Throwable failure = result.error();
                if (isRecoverableRemoteFailure(failure)) {
                    return resumeAfterRemoteFailure(data, current, failure);
                }
                throw failRemoteQuery(current, data.agentName(), failure);
            }
            log.info("Orchestrator query remote result received ({} chars), building resume",
                result.answer().length());
            return Optional.of(buildResumeRequest(current, result.answer(), data.toolCallId(), data.toolName()));
        }
        return Optional.empty(); // non-a2a_delegate or error → stop loop, return interrupt to caller
    }

    private static String remoteFailureContent(Throwable failure) {
        String code = RemoteAgentException.CODE_REMOTE_ERROR;
        String error = "remote A2A call failed";
        Optional<RemoteAgentException> remoteFailure = remoteFailure(failure);
        if (remoteFailure.isPresent()) {
            code = remoteFailure.get().getCode();
            error = remoteFailureMessage(code);
        }
        if (remoteFailure.isEmpty() && unwrapFailure(failure) instanceof java.util.concurrent.TimeoutException) {
            code = RemoteAgentException.CODE_REMOTE_TIMEOUT;
            error = "remote A2A call timed out";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.put("code", code);
        return GSON.toJson(payload);
    }

    private static String remoteFailureMessage(String code) {
        return switch (code) {
            case RemoteAgentException.CODE_REMOTE_TIMEOUT -> "remote A2A call timed out";
            case RemoteAgentException.CODE_REMOTE_STREAM_CLOSED -> "remote A2A stream closed before a terminal event";
            default -> "remote A2A call failed";
        };
    }

    private static boolean isRecoverableRemoteFailure(Throwable failure) {
        Throwable cause = unwrapFailure(failure);
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        return cause instanceof RemoteAgentException remoteAgentFailure
                && (RemoteAgentException.CODE_REMOTE_TIMEOUT.equals(remoteAgentFailure.getCode())
                        || RemoteAgentException.CODE_REMOTE_STREAM_CLOSED.equals(remoteAgentFailure.getCode()));
    }

    private static Optional<RemoteAgentException> remoteFailure(Throwable failure) {
        Throwable cause = unwrapFailure(failure);
        return cause instanceof RemoteAgentException remoteAgentFailure
                ? Optional.of(remoteAgentFailure)
                : Optional.empty();
    }

    private static Throwable unwrapFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private Optional<ServeRequest> remoteInputRequiredResponse(Map<String, Object> interruptData,
            QueryResponse response, ServeRequest current, InterruptData data, RemoteInputRequiredException rie) {
        Map<String, Object> result = queryInputRequiredResult(response, rie.getMessage());
        response.setResult(result);
        saveShadowTask(current.getConversationId(), data.agentName(), cardResolver.resolveJsonRpcUrl(data.agentName()),
                rie.getRemoteTaskId(), data.streamMode());
        return Optional.empty();
    }

    private QueryResumeResult pendingRemoteInputRequiredResponse(ServeRequest current, Task pending, String agentName,
            String streamMode, RemoteInputRequiredException rie) {
        saveShadowTask(current.getConversationId(), agentName, metadataString(pending, "_remote_url"),
                remoteTaskIdOrExisting(rie, pending), streamMode);
        QueryResponse response = new QueryResponse(queryInputRequiredResult(null, rie.getMessage()),
                current.getConversationId());
        return QueryResumeResult.respond(response);
    }

    private static String remoteTaskIdOrExisting(RemoteInputRequiredException rie, Task pending) {
        String remoteTaskId = rie.getRemoteTaskId();
        return remoteTaskId != null && !remoteTaskId.isBlank()
                ? remoteTaskId
                : metadataString(pending, "_remote_task_id");
    }

    private static Map<String, Object> queryInputRequiredResult(QueryResponse response, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (response != null && response.getResult() instanceof Map<?, ?> resultMap) {
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            result.put("role", "assistant");
        }
        result.put("content", message);
        result.put("_interrupt", Map.of("message", message));
        return result;
    }

    private Optional<ServeRequest> failRemoteStream(ServeRequest current, String agentName,
            QueryStreamObserver observer, Throwable cause, boolean terminallyNotified) {
        RemoteAgentException failure = remoteFailure(agentName, cause);
        log.error("Remote call '{}' failed for conversation_id={}", agentName, current.getConversationId(), failure);
        deleteShadowTask(shadowTaskId(current.getConversationId()));
        // The capturing wrapper may have already forwarded onError to the
        // passthrough (client) observer in SSE mode — honour the observer
        // contract by not double-notifying.
        if (!terminallyNotified && !observer.isCancelled()) {
            try {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, remoteFailureBody(agentName)));
            } finally {
                observer.onError(failure);
            }
        }
        return Optional.empty();
    }

    private RemoteAgentException failRemoteQuery(ServeRequest current, String agentName, Throwable cause) {
        RemoteAgentException failure = remoteFailure(agentName, cause);
        log.error("Remote call '{}' failed for conversation_id={}", agentName, current.getConversationId(), failure);
        deleteShadowTask(shadowTaskId(current.getConversationId()));
        return failure;
    }

    private static RemoteAgentException remoteFailure(String agentName, Throwable cause) {
        Throwable actualCause = cause != null ? cause : new IllegalStateException("Remote call failed without a cause");
        return new RemoteAgentException("Remote agent '" + agentName + "' call failed", actualCause);
    }

    private static Map<String, Object> remoteFailureBody(String agentName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("code", "REMOTE_A2A_CALL_FAILED");
        body.put("error", "remote agent call failed");
        body.put("agent", agentName);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInterruptFromResponse(QueryResponse response) {
        if (response.getResult() instanceof Map<?, ?> m && m.get("_interrupt") instanceof Map<?, ?> interrupt) {
            return (Map<String, Object>) interrupt;
        }
        return new LinkedHashMap<>();
    }

    private static QueryResponse buildInterruptQueryResponse(String convId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("_interrupt", Map.of("message", "Remote agent requires input"));
        return new QueryResponse(result, convId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static InterruptData resolveInterruptDataFromMap(Map<String, Object> data) {
        Object contextObj = data.get("context");
        Map context = contextObj instanceof Map ? (Map) contextObj : null;
        String kind = context != null && context.get("_interrupt_kind") instanceof String s
                ? s
                : data.get("agentName") instanceof String
                        ? InterruptData.KIND_A2A_DELEGATE
                        : InterruptData.KIND_ASK_USER;
        String agentName = context != null && context.get("agentName") instanceof String an
                ? an
                : data.get("agentName") instanceof String an2 ? an2 : "";
        String message = data.get("message") instanceof String s ? s : "";
        String toolCallId = data.get("toolCallId") instanceof String s ? s : "";
        String toolName = data.get("toolName") instanceof String s ? s : "";
        String streamMode = context != null && context.get("_stream_mode") instanceof String s
                ? s
                : data.get("_stream_mode") instanceof String s2 ? s2 : "";
        return new InterruptData(kind, agentName, message, toolCallId, toolName, streamMode);
    }

    private List<Task> findPending(String conversationId) {
        // Use get() instead of list() — list() goes through transformTask()
        // which rebuilds the Task and may drop metadata in some code paths.
        Task task = taskStore.get(shadowTaskId(conversationId));
        if (task != null && task.status() != null && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            return List.of(task);
        }
        return List.of();
    }

    /**
     * Builds this agent's shadow task id for a conversation. The id is namespaced
     * by agent identity so that, when several agents share one task store (e.g. the
     * same Redis) and the conversation id is passed through unchanged, each agent's
     * shadow task occupies a distinct key instead of overwriting the others.
     *
     * @param conversationId
     *            the passed-through conversation id
     * @return the namespaced shadow task id
     */
    private String shadowTaskId(String conversationId) {
        return SHADOW_KEY_PREFIX + agentId + ":" + conversationId;
    }

    private void deleteShadowTask(String taskId) {
        taskStore.delete(taskId);
    }

    private void saveShadowTask(String convId, String agentName, String url) {
        saveShadowTask(convId, agentName, url, "", "");
    }

    private void saveShadowTask(String convId, String agentName, String url, String remoteTaskId, String streamMode) {
        log.info("Orchestrator saveShadowTask convId={} agent={} remoteTaskId={} streamMode={}", convId, agentName,
                remoteTaskId, streamMode);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (url != null) {
            meta.put("_remote_url", url);
        }
        if (agentName != null) {
            meta.put("_agent_name", agentName);
        }
        if (remoteTaskId != null && !remoteTaskId.isBlank()) {
            meta.put("_remote_task_id", remoteTaskId);
        }
        if (streamMode != null && !streamMode.isBlank()) {
            meta.put("_stream_mode", streamMode);
        }
        taskStore.save(Task.builder().id(shadowTaskId(convId)).contextId(convId)
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(meta.isEmpty() ? null : meta).build(), true);
    }

    private ServeRequest buildResumeRequest(ServeRequest original, String toolContent, String toolCallId,
            String toolName) {
        log.info("Orchestrator buildResumeRequest convId={} toolName={} toolCallId={} toolContentLen={}",
                original.getConversationId(), toolName, toolCallId, toolContent != null ? toolContent.length() : 0);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", toolContent);
        Map<String, Object> messageMetadata = original.lastUserMessageMetadata();
        if (!messageMetadata.isEmpty()) {
            userMsg.put("metadata", messageMetadata);
        }
        messages.add(userMsg);
        ServeRequest resumeReq = new ServeRequest();
        resumeReq.setConversationId(original.getConversationId());
        resumeReq.setStream(original.isStream());
        resumeReq.setMessages(messages);
        resumeReq.setUserId(original.getUserId());
        resumeReq.setSpaceId(original.getSpaceId());
        resumeReq.setTenantId(original.getTenantId());
        resumeReq.setMetadata(original.getMetadata());
        return resumeReq;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static InterruptData resolveInterruptData(QueryChunk chunk) {
        if (!QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
            return InterruptData.EMPTY;
        }
        Object rawObj = chunk.getData();
        if (!(rawObj instanceof Map)) {
            return InterruptData.EMPTY;
        }
        var raw = (Map) rawObj;
        Object contextObj = raw.get("context");
        Map context = contextObj instanceof Map ? (Map) contextObj : null;
        String kind = context != null && context.get("_interrupt_kind") instanceof String s
                ? s
                : raw.get("agentName") instanceof String
                        ? InterruptData.KIND_A2A_DELEGATE
                        : InterruptData.KIND_ASK_USER;
        String agentName = context != null && context.get("agentName") instanceof String an
                ? an
                : raw.get("agentName") instanceof String an2 ? an2 : "";
        String message = raw.get("message") instanceof String s ? s : "";
        String toolCallId = raw.get("toolCallId") instanceof String s ? s : "";
        String toolName = raw.get("toolName") instanceof String s ? s : "";
        String streamMode = context != null && context.get("_stream_mode") instanceof String s
                ? s
                : raw.get("_stream_mode") instanceof String s2 ? s2 : "";
        return new InterruptData(kind, agentName, message, toolCallId, toolName, streamMode);
    }

    /**
     * Safely extracts a string from task metadata.
     *
     * @param task
     *            the task
     * @param key
     *            the metadata key
     * @return the metadata value as string, or empty string if not present
     */
    private static String metadataString(Task task, String key) {
        if (task == null || task.metadata() == null) {
            return "";
        }
        Object value = task.metadata().get(key);
        return value instanceof String s ? s : "";
    }
}
