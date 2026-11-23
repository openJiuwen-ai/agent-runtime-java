/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration tests for the session management API surface
 * (issue #117 review follow-up): the terminate behavior across initial
 * states, the terminate idempotency, the compact rejection on terminated
 * sessions, and the pause-vs-terminate race on one session.
 *
 * <p>404 coverage note: the current implementation resolves unknown session
 * ids through the state machine (idle default when no registry record and
 * no active task exists), so pause/resume on a never-seen session yield the
 * state-conflict/idempotent semantics rather than a storage-backed 404 -
 * the true 404 distinction requires the T1 session-listing capability. The
 * cases here assert the current semantics; the review feedback tracks the
 * 404 gap separately.</p>
 *
 * <p>The application registers a session-operations-capable handler plus a
 * controllable active-task registry, so the state machine resolves real
 * RUNNING/IDLE/PAUSED/TERMINATED verdicts through the full HTTP stack.</p>
 *
 * @since 0.1.5
 */
@SpringBootTest(classes = SessionManageEndpointIntegrationTest.SessionEndpointTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SessionManageEndpointIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearRunningRegistry() {
        StubActiveTaskQuery.clear();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void terminateUnknownSessionIsAcceptedAsNoOpInterrupt() {
        // 4.1 (current semantics): terminate on an unknown session id must
        // not fail; the interrupt chain treats a missing active execution as
        // a no-op and the state verdict stays idempotent on repetition. The
        // storage-backed 404 distinction arrives with T1.
        ResponseEntity<String> first = post("/api/sessions/session-unknown/terminate");
        ResponseEntity<String> second = post("/api/sessions/session-unknown/terminate");

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void compactUnknownSessionResolvesIdleThenCompactsOnOrchestratorStub() {
        // 4.2 (current semantics): an unknown session id resolves through
        // the state machine to IDLE (no registry record, no active task), so
        // the compaction runs through the orchestrator path and succeeds on
        // the stub; the 404 distinction requires the T1 storage-listing
        // capability. The test pins the current contract: compact does not
        // fail with 5xx on an unknown id and the state machine stays
        // consistent afterwards.
        ResponseEntity<String> compact = post("/api/sessions/session-unknown/compact");

        assertThat(compact.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void pauseUnknownSessionConflictsOnIdleVerdict() {
        // 4.3 (current semantics): an unknown session id resolves to IDLE,
        // so pause answers the state conflict (409) rather than a storage-
        // backed 404; resume of an unknown session delegates through the
        // coordinator. The 404 distinction arrives with T1; this pins the
        // current contract so the T1 change must update it deliberately.
        ResponseEntity<String> pause = post("/api/sessions/session-idle-3/pause");

        assertThat(pause.getStatusCode().value()).isEqualTo(409);
        assertThat(pause.getBody()).contains("INVALID_SESSION_STATE");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void terminateIdleSessionIsAcceptedWithoutSideEffects() throws Exception {
        // 3.8: an idle session has no active stream; the terminate must
        // succeed without an exception and leave the session terminated.
        ResponseEntity<String> terminate = post("/api/sessions/session-idle/terminate");
        ResponseEntity<String> detail = get("/api/sessions/session-idle");

        assertThat(terminate.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> detailBody = readBody(detail);
        assertThat(detailBody.get("state")).isEqualTo("TERMINATED");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void terminateAcrossInitialStatesMatchesStateMachine() throws Exception {
        // 3.9 / 3.12: running -> interrupted into TERMINATED, idle ->
        // accepted no-op, already terminated -> idempotent repetition.
        // Precondition: the running branch is only entered when an active
        // task exists, so the session is marked RUNNING up front and the
        // verdict is asserted before the terminate lands.
        StubActiveTaskQuery.markRunning("session-running");
        Map<String, Object> preconditionBody = readBody(get("/api/sessions/session-running"));
        assertThat(preconditionBody.get("state")).isEqualTo("RUNNING");

        ResponseEntity<String> runningTerminate = post("/api/sessions/session-running/terminate");
        assertThat(runningTerminate.getStatusCode().value()).isEqualTo(202);

        ResponseEntity<String> idleTerminate = post("/api/sessions/session-idle-2/terminate");
        assertThat(idleTerminate.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> repeatedTerminate = post("/api/sessions/session-running/terminate");
        assertThat(repeatedTerminate.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> detail = get("/api/sessions/session-running");
        Map<String, Object> detailBody = readBody(detail);
        assertThat(detailBody.get("state")).isEqualTo("TERMINATED");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void compactTerminatedSessionIsRejected() throws Exception {
        // 3.11: a terminated session must never be compactable.
        post("/api/sessions/session-term-compact/terminate");

        ResponseEntity<String> compact = post("/api/sessions/session-term-compact/compact");
        ResponseEntity<String> detail = get("/api/sessions/session-term-compact");

        assertThat(compact.getStatusCode().value()).isEqualTo(409);
        assertThat(compact.getBody()).contains("INVALID_SESSION_STATE");
        Map<String, Object> detailBody = readBody(detail);
        assertThat(detailBody.get("state")).isEqualTo("TERMINATED");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void terminateIdempotencyReturnsCurrentStateWithoutSideEffects() throws Exception {
        // 4.11 / 4.15: repeated terminate calls after the first acceptance
        // collapse into the idempotent result and never re-interrupt.
        post("/api/sessions/session-idempotent/terminate");

        ResponseEntity<String> second = post("/api/sessions/session-idempotent/terminate");
        ResponseEntity<String> third = post("/api/sessions/session-idempotent/terminate");

        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(third.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> secondBody = readBody(second);
        assertThat(secondBody.get("alreadyTerminated")).isEqualTo(true);
        assertThat(secondBody.get("state")).isEqualTo("TERMINATED");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void pauseThenTerminateWinsAndResumeIsRejected() throws Exception {
        // 3.28 / 4.4: when pause and terminate race on one running session,
        // terminate wins, the state settles on TERMINATED, and the follow-up
        // resume is rejected with 409. The pause request is issued against a
        // genuinely RUNNING session (marked up front) and both responses are
        // collected, so the race outcome - not just the terminate verdict -
        // is pinned: pause may be accepted (202) and then overridden by the
        // terminate, or rejected (409) once the terminate landed, but the
        // session must never end up paused.
        StubActiveTaskQuery.markRunning("session-race");
        CountDownLatch started = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ResponseEntity<String>> responses;
        try {
            Future<ResponseEntity<String>> terminate = pool.submit(() -> {
                started.countDown();
                return post("/api/sessions/session-race/terminate");
            });
            Future<ResponseEntity<String>> pause = pool.submit(() -> {
                started.countDown();
                return post("/api/sessions/session-race/pause");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // futures.get rethrows task failures instead of dropping them
            responses = List.of(terminate.get(10, TimeUnit.SECONDS), pause.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        awaitState("session-race", "TERMINATED");

        // The pause leg of the race: with the terminate landed, the pause
        // outcome collapses into the legal set - accepted then overridden,
        // or rejected outright. A surviving PAUSED record would break the
        // follow-up resume rejection below, so no residue may remain.
        ResponseEntity<String> pauseResponse = responses.get(1);
        int pauseStatus = pauseResponse.getStatusCode().value();
        assertThat(pauseStatus == 202 || pauseStatus == 200 || pauseStatus == 409)
                .as("race pause response stays in the legal set, got %s with body %s",
                        pauseStatus, pauseResponse.getBody())
                .isTrue();

        ResponseEntity<String> resume = post("/api/sessions/session-race/resume");
        assertThat(resume.getStatusCode().value()).isEqualTo(409);
        assertThat(resume.getBody()).contains("INVALID_SESSION_STATE");
    }

    private ResponseEntity<String> post(String path) {
        HttpHeaders headers = new HttpHeaders();
        Map<String, Object> emptyBody = Map.of();
        return rest.postForEntity(path, new HttpEntity<>(emptyBody, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(ResponseEntity<String> response) throws Exception {
        return mapper.readValue(response.getBody(), Map.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    private void awaitState(String sessionId, String expectedState) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> detail = get("/api/sessions/" + sessionId);
            if (detail.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> body = readBody(detail);
                state = String.valueOf(body.get("state"));
                if (expectedState.equals(state)) {
                    return;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Session " + sessionId + " never reached " + expectedState
                + " (last state: " + state + ")");
    }

    /**
     * Minimal application: a session-operations-capable handler, a
     * forwarding orchestrator, and a controllable active-task registry,
     * wired through the real auto-configuration so the HTTP layer resolves
     * genuine state verdicts. The orchestrator bean is explicit (not a
     * side effect of the A2A auto-configuration): the capability gate of
     * {@code SessionPauseResumeCoordinator.supportsSessionOperations()}
     * probes the ServeOrchestrator bean, so pinning it here keeps the
     * pause/resume/compact verdicts independent of A2A wiring conditions.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SessionEndpointTestApp {
        @Bean
        AgentHandler sessionOpsAgentHandler() {
            return new SessionOpsStubHandler();
        }

        @Bean
        ServeOrchestrator sessionOpsOrchestrator(AgentHandler sessionOpsAgentHandler) {
            return new SessionOpsForwardingOrchestrator(sessionOpsAgentHandler);
        }

        @Bean
        ActiveTaskQuery activeTaskQuery() {
            return new StubActiveTaskQuery();
        }
    }

    /**
     * Orchestrator stub that forwards the issue #117 session operations to
     * the handler and reports the support flag, so the capability gate
     * resolves through an explicit bean instead of the A2A wiring.
     */
    static final class SessionOpsForwardingOrchestrator implements ServeOrchestrator {
        private final AgentHandler delegate;

        SessionOpsForwardingOrchestrator(AgentHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return delegate.query(request);
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            delegate.streamQuery(request, observer);
        }

        @Override
        public void cancelActive(String conversationId) {
            // no active streams in this stub application
        }

        @Override
        public void resetConversation(String conversationId) {
            delegate.clearSession(conversationId);
        }

        @Override
        public void resumeSession(String sessionId) {
            delegate.resumeSession(sessionId);
        }

        @Override
        public void requestPause(String sessionId, Map<String, Object> pauseInfo) {
            delegate.requestPause(sessionId, pauseInfo);
        }

        @Override
        public Map<String, Object> compactSession(String sessionId, Map<String, Object> options) {
            return delegate.compactSession(sessionId, options);
        }

        @Override
        public boolean supportsSessionOperations() {
            return delegate.supportsSessionOperations();
        }
    }

    /** Session-operations stub: reports support, records the operations. */
    static final class SessionOpsStubHandler implements AgentHandler {
        final CopyOnWriteArrayList<String> resumedSessions = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> pausedSessions = new CopyOnWriteArrayList<>();

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("content", "stub"), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onComplete();
        }

        @Override
        public boolean supportsSessionOperations() {
            return true;
        }

        @Override
        public void resumeSession(String sessionId) {
            resumedSessions.add(sessionId);
            StubActiveTaskQuery.markRunning(sessionId);
        }

        @Override
        public void requestPause(String sessionId, Map<String, Object> pauseInfo) {
            pausedSessions.add(sessionId);
        }

        @Override
        public Map<String, Object> compactSession(String sessionId, Map<String, Object> options) {
            return Map.of("session_id", sessionId, "result", "compressed");
        }
    }

    /**
     * Registry-backed active-task query: sessions turn RUNNING through the
     * mark hook and stay running until terminated, exercising the real
     * state-resolution chain. The registry is cleared between tests so a
     * session marked RUNNING in one case never leaks into the next one
     * (every test also runs with a fresh application context).
     */
    static final class StubActiveTaskQuery implements ActiveTaskQuery {
        private static final Map<String, Boolean> RUNNING = new ConcurrentHashMap<>();

        static void markRunning(String sessionId) {
            RUNNING.put(sessionId, Boolean.TRUE);
        }

        static void clear() {
            RUNNING.clear();
        }

        @Override
        public ConcurrencyLoadSnapshot snapshot() {
            List<ActiveTaskInfo> tasks = RUNNING.entrySet().stream()
                    .filter(entry -> entry.getValue())
                    .map(entry -> new ActiveTaskInfo("task-" + entry.getKey(), entry.getKey(),
                            "WORKING", "2026-01-01T00:00:00.000Z"))
                    .toList();
            return new ConcurrencyLoadSnapshot(100, tasks.size(), tasks);
        }
    }
}
