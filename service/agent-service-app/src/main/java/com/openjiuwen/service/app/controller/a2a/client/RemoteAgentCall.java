/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Objects;

/**
 * Immutable parameter object for {@link RemoteAgentCaller#call}.
 *
 * <p>Carries the addressing coordinates (agentId, original ServeRequest) and the
 * optional upstream {@code response_content} that Caller implementations may
 * consume to append an assistant message to the forwarded request's
 * {@code messages} array (see spec §4.9). The {@code A2ARemoteAgentClient}
 * ignores {@link #responseContent} to preserve logic-equivalence with the
 * original A2A remote client behavior; the A2AGateway / InProcess Caller
 * implementations (deployment module) consume it.
 *
 * <p>The optional {@link #message} override supports the legacy
 * {@code a2a_delegate} interrupt path where the orchestrator forwards an
 * explicit message distinct from {@code serveRequest.lastUserQuery()}. When
 * {@code null}, Caller implementations use {@code serveRequest.lastUserQuery()}.
 *
 * <p>The {@link #streaming} flag preserves the legacy split between
 * {@code callStreaming} (streaming SDK client, observer-routed errors) and
 * {@code callSync} (non-streaming SDK client, throw-routed errors). The SPI
 * unifies both into a single {@link RemoteAgentCaller#call} entry point, but
 * the implementation branches on this flag so that sync callers keep seeing
 * {@link RemoteInputRequiredException} / {@link RemoteAgentException} thrown
 * directly (as the legacy {@code callSync} did), and streaming callers keep
 * receiving terminal signals via the observer (as the legacy
 * {@code callStreaming} did).
 *
 * @param agentId         target remote agent identifier (A2A Gateway {@code agentCard} path segment)
 * @param serveRequest    the original serve request to forward (Caller decides whether to mutate messages)
 * @param responseContent optional upstream workflow {@code response_content}; may be {@code null}
 * @param contextId       optional conversation context id for resume; {@code null} for a new task
 * @param taskId          optional remote task id to resume; {@code null} for a new task
 * @param message         optional message override; when {@code null}, Caller uses {@code serveRequest.lastUserQuery()}
 * @param streaming       {@code true} for streaming SDK client + observer-routed errors;
 *                        {@code false} for non-streaming SDK client + throw-routed errors
 */
public record RemoteAgentCall(
        String agentId,
        ServeRequest serveRequest,
        String responseContent,
        String contextId,
        String taskId,
        String message,
        boolean streaming
) {
    /**
     * Canonical constructor with validation.
     *
     * @param agentId         target agent id; must be non-blank
     * @param serveRequest    the serve request; must be non-null
     * @param responseContent optional upstream response content
     * @param contextId       optional context id
     * @param taskId          optional remote task id
     * @param message         optional message override
     * @param streaming       whether to use streaming SDK client + observer-routed errors
     */
    public RemoteAgentCall {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(serveRequest, "serveRequest");
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
    }

    /**
     * Convenience constructor for a new-task streaming call without response
     * content or message override.
     *
     * @param agentId      target agent id
     * @param serveRequest the serve request
     */
    public RemoteAgentCall(String agentId, ServeRequest serveRequest) {
        this(agentId, serveRequest, null, null, null, null, true);
    }

    /**
     * Convenience constructor with responseContent and addressing but no message
     * override; defaults to streaming mode.
     *
     * @param agentId         target agent id
     * @param serveRequest    the serve request
     * @param responseContent optional upstream response content
     * @param contextId       optional context id
     * @param taskId          optional remote task id
     */
    public RemoteAgentCall(String agentId, ServeRequest serveRequest, String responseContent,
            String contextId, String taskId) {
        this(agentId, serveRequest, responseContent, contextId, taskId, null, true);
    }

    /**
     * Convenience constructor with message override; defaults to streaming mode.
     *
     * @param agentId         target agent id
     * @param serveRequest    the serve request
     * @param responseContent optional upstream response content
     * @param contextId       optional context id
     * @param taskId          optional remote task id
     * @param message         optional message override
     */
    public RemoteAgentCall(String agentId, ServeRequest serveRequest, String responseContent,
            String contextId, String taskId, String message) {
        this(agentId, serveRequest, responseContent, contextId, taskId, message, true);
    }
}
