/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 * <p>Every service call is blocking (state queries hit the task store,
 * compaction may run model calls and checkpoint writes), so each endpoint
 * wraps its work in {@code Mono.fromCallable} on the bounded-elastic
 * scheduler to keep the Netty event loops free.</p>
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
     * Lists managed sessions with paging and state filtering; the storage
     * listing capability is not wired yet, so the endpoint reports itself as
     * not implemented instead of answering with a forged empty page.
     *
     * @param headers ingress headers for authorization
     * @param state optional state filter
     * @param page optional page index
     * @param size optional page size
     * @return 501 until the storage listing capability (T1) lands
     */
    @GetMapping(value = AgentServicePaths.SESSIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "list")
    public Mono<ResponseEntity<Map<String, Object>>> listSessions(HttpHeaders headers,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", "sessions listing requires the storage listing capability (T1)")));
    }

    /**
     * Returns the lifecycle detail of one session.
     *
     * @param headers ingress headers for authorization
     * @param sessionId target session id
     * @return 200 with the detail, 404 when unknown, or 503 when the state
     *         query fails
     */
    @GetMapping(value = AgentServicePaths.SESSION_DETAIL, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "read")
    public Mono<ResponseEntity<Map<String, Object>>> sessionDetail(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> detail = service.sessionDetail(sessionId);
            if (detail.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.<String, Object>of("error", "session not found"));
            }
            return ResponseEntity.ok(detail);
        }).onErrorResume(SessionManageService.SessionStateException.class, e -> Mono.just(mapStateUnavailable(e)))
                .subscribeOn(Schedulers.boundedElastic());
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
     * @return 202 accepted, 200 idempotent, 409 state conflict, 404 unknown,
     *         or 503 when the state query fails
     */
    @PostMapping(value = AgentServicePaths.SESSION_PAUSE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "pause")
    public Mono<ResponseEntity<Map<String, Object>>> pause(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        return Mono.fromCallable(() -> {
            SessionManageService.PauseOutcome outcome = service.pause(sessionId,
                    headers.getFirst("X-User-Id"), headers.getFirst("X-Tenant-ID"),
                    headers.getFirst("X-Pause-Reason"));
            return ResponseEntity.status(outcome.status()).body(outcome.body());
        }).onErrorResume(SessionManageService.SessionStateException.class, e -> Mono.just(mapStateUnavailable(e)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Resumes a paused session; asynchronous acceptance (202).
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 202 accepted, 409 state conflict, 429 admission rejected, 404
     *         unknown, or 503 when the state query fails
     */
    @PostMapping(value = AgentServicePaths.SESSION_RESUME, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "resume")
    public Mono<ResponseEntity<Map<String, Object>>> resume(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        return Mono.fromCallable(() -> {
            SessionManageService.ResumeOutcome outcome = service.resume(sessionId, headers.getFirst("X-User-Id"));
            return ResponseEntity.status(outcome.status()).body(outcome.body());
        }).onErrorResume(SessionManageService.SessionStateException.class, e -> Mono.just(mapStateUnavailable(e)))
                .subscribeOn(Schedulers.boundedElastic());
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
        return Mono.fromCallable(() -> {
            SessionManageService.TerminateOutcome outcome = service.terminate(sessionId,
                    headers.getFirst("X-User-Id"));
            return ResponseEntity.status(outcome.status()).body(outcome.body());
        }).onErrorResume(SessionManageService.SessionStateException.class, e -> Mono.just(mapStateUnavailable(e)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Compacts an idle session; running and paused sessions are rejected.
     *
     * @param headers ingress headers for authorization and operator identity
     * @param sessionId target session id
     * @return 200 with the compression outcome, 409 state conflict, 404
     *         unknown, or 503 when the state query fails
     */
    @PostMapping(value = AgentServicePaths.SESSION_COMPACT, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "compact")
    public Mono<ResponseEntity<Map<String, Object>>> compact(HttpHeaders headers,
            @PathVariable("sessionId") String sessionId) {
        return Mono.fromCallable(() -> {
            SessionManageService.CompactOutcome outcome = service.compact(sessionId,
                    headers.getFirst("X-User-Id"), null);
            return ResponseEntity.status(outcome.status()).body(outcome.body());
        }).onErrorResume(SessionManageService.SessionStateException.class, e -> Mono.just(mapStateUnavailable(e)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Maps an undecidable session state to the 503 fail-closed response.
     *
     * @param exception the state resolution failure
     * @return the 503 response entity
     */
    private ResponseEntity<Map<String, Object>> mapStateUnavailable(
            SessionManageService.SessionStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "SESSION_STATE_UNAVAILABLE",
                        "detail", "the session state could not be determined; retry after the task store recovers"));
    }
}
