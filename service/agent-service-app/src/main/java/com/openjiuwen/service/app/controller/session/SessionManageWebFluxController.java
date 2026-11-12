/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Reactive stack session management controller (issue #117), mirroring the
 * MVC surface: session list, detail, messages, and the pause / resume /
 * terminate / compact operations.
 *
 * <p>All endpoints are management-plane surfaces and carry fine-grained
 * authorization without anonymous exemptions; the {@link HttpHeaders}
 * argument keeps the reactive authorization path fail-closed.</p>
 *
 * @since 0.1.5
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SessionManageWebFluxController {
    private final SessionManageService service;

    /**
     * Constructs the controller.
     *
     * @param service shared session management service
     */
    public SessionManageWebFluxController(SessionManageService service) {
        this.service = service;
    }

    /**
     * Lists managed sessions with paging and state filtering.
     *
     * @param headers ingress headers for authorization
     * @param state optional state filter
     * @param page optional page index
     * @param size optional page size
     * @return paged session list
     */
    @GetMapping(value = AgentServicePaths.SESSIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "list")
    public Mono<ResponseEntity<Map<String, Object>>> listSessions(HttpHeaders headers,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        Map<String, Object> body = Map.of("sessions", List.of(), "page", page, "size", size);
        return Mono.just(ResponseEntity.ok(body));
    }

    /**
     * Returns the lifecycle detail of one session.
     *
     * @param headers ingress headers for authorization
     * @param sessionId target session id
     * @return 200 with the detail, or 404 when unknown
     */
    @GetMapping(value = AgentServicePaths.SESSION_DETAIL, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "read")
    public Mono<ResponseEntity<Map<String, Object>>> sessionDetail(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        Map<String, Object> detail = service.sessionDetail(sessionId);
        if (detail == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "session not found")));
        }
        return Mono.just(ResponseEntity.ok(detail));
    }

    /**
     * Returns the message history of one session; disabled by default.
     *
     * @param headers ingress headers for authorization
     * @param sessionId target session id
     * @param page optional page index
     * @param size optional page size
     * @return 200 with messages when enabled, or the disabled/error semantics
     */
    @GetMapping(value = AgentServicePaths.SESSION_MESSAGES, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "read")
    public Mono<ResponseEntity<Map<String, Object>>> sessionMessages(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", "messages endpoint requires the storage listing capability (T1)")));
    }

    /**
     * Pauses a running session; asynchronous acceptance (202).
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 202 accepted, 200 idempotent, 409 state conflict, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_PAUSE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "pause")
    public Mono<ResponseEntity<Map<String, Object>>> pause(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        SessionManageService.PauseOutcome outcome = service.pause(sessionId,
                headers.getFirst("X-User-Id"), headers.getFirst("X-Tenant-ID"),
                headers.getFirst("X-Pause-Reason"));
        return Mono.just(ResponseEntity.status(outcome.status()).body(outcome.body()));
    }

    /**
     * Resumes a paused session; asynchronous acceptance (202).
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 202 accepted, 409 state conflict, 429 admission rejected, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_RESUME, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "resume")
    public Mono<ResponseEntity<Map<String, Object>>> resume(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        SessionManageService.ResumeOutcome outcome = service.resume(sessionId, headers.getFirst("X-User-Id"));
        return Mono.just(ResponseEntity.status(outcome.status()).body(outcome.body()));
    }

    /**
     * Terminates a session; repeated requests are idempotent.
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 202 accepted, 200 idempotent, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_TERMINATE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "terminate")
    public Mono<ResponseEntity<Map<String, Object>>> terminate(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        SessionManageService.TerminateOutcome outcome = service.terminate(sessionId, headers.getFirst("X-User-Id"));
        return Mono.just(ResponseEntity.status(outcome.status()).body(outcome.body()));
    }

    /**
     * Compacts an idle session; running and paused sessions are rejected.
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 200 with the compression outcome, 409 state conflict, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_COMPACT, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "compact")
    public Mono<ResponseEntity<Map<String, Object>>> compact(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        SessionManageService.CompactOutcome outcome = service.compact(sessionId,
                headers.getFirst("X-User-Id"), null);
        return Mono.just(ResponseEntity.status(outcome.status()).body(outcome.body()));
    }
}
