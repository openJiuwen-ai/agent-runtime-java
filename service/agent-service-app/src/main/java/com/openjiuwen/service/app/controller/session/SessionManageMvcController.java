/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MVC stack session management controller (issue #117): session list,
 * detail, messages, and the pause / resume / terminate / compact operations.
 *
 * <p>All endpoints are management-plane surfaces and carry fine-grained
 * authorization without anonymous exemptions.</p>
 *
 * @since 0.1.5
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionManageMvcController {
    private final SessionManageService service;

    /**
     * Constructs the controller.
     *
     * @param service shared session management service
     */
    public SessionManageMvcController(SessionManageService service) {
        this.service = service;
    }

    /**
     * Lists managed sessions with paging and state filtering; the storage
     * listing capability is not wired yet, so the endpoint reports itself as
     * not implemented instead of answering with a forged empty page.
     *
     * @param state optional state filter
     * @param page optional page index
     * @param size optional page size
     * @return 501 until the storage listing capability (T1) lands
     */
    @GetMapping(value = AgentServicePaths.SESSIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "list")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", "sessions listing requires the storage listing capability (T1)"));
    }

    /**
     * Returns the lifecycle detail of one session.
     *
     * @param sessionId target session id
     * @return 200 with the detail, or 404 when unknown
     */
    @GetMapping(value = AgentServicePaths.SESSION_DETAIL, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "read")
    public ResponseEntity<Map<String, Object>> sessionDetail(@PathVariable("sessionId") String sessionId) {
        Map<String, Object> detail = service.sessionDetail(sessionId);
        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "session not found"));
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * Returns the message history of one session; disabled by default.
     *
     * @param sessionId target session id
     * @param page optional page index
     * @param size optional page size
     * @return 200 with messages when enabled, or the disabled/error semantics
     */
    @GetMapping(value = AgentServicePaths.SESSION_MESSAGES, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "read")
    public ResponseEntity<Map<String, Object>> sessionMessages(@PathVariable("sessionId") String sessionId,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", "messages endpoint requires the storage listing capability (T1)"));
    }

    /**
     * Pauses a running session; asynchronous acceptance (202).
     *
     * @param sessionId target session id
     * @param userId operator identity from the ingress header
     * @param tenantId tenant identity from the gateway header, carried into
     *        the pause intent so the intent registry key matches the
     *        tenant-scoped key the executing loop consumes
     * @param reason pause reason from the dedicated header
     * @return 202 accepted, 200 idempotent, 409 state conflict, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_PAUSE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable("sessionId") String sessionId,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @RequestHeader(name = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(name = "X-Pause-Reason", required = false) String reason) {
        SessionManageService.PauseOutcome outcome = service.pause(sessionId, userId, tenantId, reason);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Resumes a paused session; asynchronous acceptance (202).
     *
     * @param sessionId target session id
     * @param userId operator identity from the ingress header
     * @return 202 accepted, 409 state conflict, 429 admission rejected, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_RESUME, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable("sessionId") String sessionId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        SessionManageService.ResumeOutcome outcome = service.resume(sessionId, userId);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Terminates a session; repeated requests are idempotent.
     *
     * @param sessionId target session id
     * @param userId operator identity from the ingress header
     * @return 202 accepted, 200 idempotent, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_TERMINATE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "terminate")
    public ResponseEntity<Map<String, Object>> terminate(@PathVariable("sessionId") String sessionId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        SessionManageService.TerminateOutcome outcome = service.terminate(sessionId, userId);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Compacts an idle session; running and paused sessions are rejected.
     *
     * @param sessionId target session id
     * @param userId operator identity from the ingress header
     * @return 200 with the compression outcome, 409 state conflict, or 404
     */
    @PostMapping(value = AgentServicePaths.SESSION_COMPACT, produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizedResource(resource = "session", action = "compact")
    public ResponseEntity<Map<String, Object>> compact(@PathVariable("sessionId") String sessionId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        SessionManageService.CompactOutcome outcome = service.compact(sessionId, userId, null);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Maps an undecidable session state to the 503 fail-closed response:
     * the servlet stack would otherwise answer a failed task-store query
     * with a generic 500, losing the retryable-failure semantics.
     *
     * @param exception the state resolution failure
     * @return the 503 response entity
     */
    @ExceptionHandler(SessionManageService.SessionStateException.class)
    public ResponseEntity<Map<String, Object>> stateUnavailable(
            SessionManageService.SessionStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "SESSION_STATE_UNAVAILABLE",
                        "detail", "the session state could not be determined; retry after the task store recovers"));
    }
}
