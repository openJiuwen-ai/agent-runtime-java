/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LifecycleIntegrationTest.LifecycleTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LifecycleIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentLifecycleManager lifecycleManager;

    @Autowired
    private ServeOrchestrator orchestrator;

    @Autowired
    private DefaultAgentReadiness readiness;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetInterruptCounter() {
        CountingInterruptHandler.reset();
        SlowStreamAgentHandler.reset();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void queryRejectedWhileShuttingDown() {
        readiness.markShuttingDown();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "conversation_id", "shutdown-c1",
                "message", "hello",
                "stream", false);

        ResponseEntity<String> resp = rest.postForEntity(
                "/v1/query", new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void streamQueryThenInterruptE2E() throws Exception {
        ServeRequest request = new ServeRequest();
        request.setConversationId("interrupt-e2e");
        request.setMessages(List.of(Map.of("role", "user", "content", "go")));

        AtomicBoolean completed = new AtomicBoolean(false);
        Thread worker = new Thread(() -> orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }));
        worker.start();

        assertThat(SlowStreamAgentHandler.awaitStarted(5, TimeUnit.SECONDS)).isTrue();
        lifecycleManager.interrupt("interrupt-e2e");
        worker.join(5000);

        assertThat(completed.get()).isTrue();
        assertThat(CountingInterruptHandler.COUNT.get()).isEqualTo(1);
    }

    @Test
    void observerCancellationDoesNotNotifyInterruptHandlers() throws Exception {
        ServeRequest request = new ServeRequest();
        request.setConversationId("observer-cancel");
        request.setMessages(List.of(Map.of("role", "user", "content", "go")));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                cancelled.set(true);
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });

        assertThat(completed.get()).isTrue();
        assertThat(CountingInterruptHandler.COUNT.get()).isZero();
    }

    @Test
    void lifecycleInterruptNotifiesHandlers() throws Exception {
        ServeRequest request = new ServeRequest();
        request.setConversationId("lifecycle-interrupt");
        request.setMessages(List.of(Map.of("role", "user", "content", "go")));

        Thread worker = new Thread(() -> orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        }));
        worker.start();

        assertThat(SlowStreamAgentHandler.awaitStarted(5, TimeUnit.SECONDS)).isTrue();
        lifecycleManager.interrupt("lifecycle-interrupt");
        worker.join(5000);

        assertThat(CountingInterruptHandler.COUNT.get()).isEqualTo(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class LifecycleTestApplication {

        @Bean
        AgentHandler slowStreamAgentHandler() {
            return new SlowStreamAgentHandler();
        }

        @Bean
        AgentInterruptHandler countingInterruptHandler() {
            return CountingInterruptHandler.INSTANCE;
        }
    }

    static final class CountingInterruptHandler implements AgentInterruptHandler {

        static final CountingInterruptHandler INSTANCE = new CountingInterruptHandler();
        static final java.util.concurrent.atomic.AtomicInteger COUNT = new java.util.concurrent.atomic.AtomicInteger();

        static void reset() {
            COUNT.set(0);
        }

        @Override
        public void interrupt(String conversationId, InterruptReason reason) {
            COUNT.incrementAndGet();
        }
    }

    static final class SlowStreamAgentHandler implements AgentHandler {

        private static volatile CountDownLatch startedLatch = new CountDownLatch(1);

        static void reset() {
            startedLatch = new CountDownLatch(1);
        }

        static boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("role", "assistant", "content", "sync"), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
            startedLatch.countDown();
            while (!observer.isCancelled()) {
                Thread.yield();
            }
            observer.onComplete();
        }
    }
}
