/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for admission control (DFX-002 S-1, S-3, S-13).
 *
 * @since 0.1.2
 */
@SpringBootTest(classes = ConcurrencyAdmissionIntegrationTest.AdmissionTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ConcurrencyAdmissionIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void resetState() {
        SlowAgent.reset();
        TestAdmissionGate.resetStatic();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_admitted_whenUnderLimit() {
        TestAdmissionGate.setMax(1);
        HttpHeaders headers = jsonHeaders();
        String body = jsonRpc("SendMessage", "conv-admit-1", "hello");

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMessage_rejected503_whenAtLimit() throws InterruptedException {
        TestAdmissionGate.setMax(1);
        HttpHeaders headers = jsonHeaders();

        ExecutorService blockingPool = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        blockingPool.submit(() ->
            rest.postForEntity("http://localhost:" + port + "/a2a",
                    new HttpEntity<>(jsonRpc("SendStreamingMessage", "conv-slow", "slow"), headers), String.class)
        );
        assertThat(SlowAgent.awaitStarted(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/a2a",
                new HttpEntity<>(jsonRpc("SendMessage", "conv-reject", "reject"), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        blockingPool.shutdownNow();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unlimited_allRequestsAdmitted() {
        TestAdmissionGate.setMax(-1);
        HttpHeaders headers = jsonHeaders();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> response = rest.postForEntity(
                    "http://localhost:" + port + "/a2a",
                    new HttpEntity<>(jsonRpc("SendMessage", "conv-unlim-" + i, "hello"), headers), String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String jsonRpc(String method, String contextId, String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"" + method + "\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\","
                + "\"contextId\":\"" + contextId + "\",\"parts\":[{\"kind\":\"text\",\"text\":\""
                + text + "\"}]}}}";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class AdmissionTestApp {
        @Bean
        AgentHandler slowAgentHandler() {
            return new SlowAgent();
        }

        @Bean
        TaskAdmissionGate admissionGate() {
            return new TestAdmissionGate();
        }
    }

    static final class TestAdmissionGate implements TaskAdmissionGate {
        private static volatile int max = -1;

        private final AtomicInteger count = new AtomicInteger(0);

        static void setMax(int value) {
            max = value;
        }

        static void resetStatic() {
            max = -1;
        }

        @Override
        public boolean tryAcquire() {
            if (max < 0) {
                return true;
            }
            return count.getAndIncrement() < max;
        }

        @Override
        public void release() {
            count.updateAndGet(v -> Math.max(0, v - 1));
        }

        @Override
        public int currentCount() {
            return count.get();
        }

        @Override
        public int limit() {
            return max;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void reset() {
            count.set(0);
        }
    }

    static final class SlowAgent implements AgentHandler {
        private static volatile CountDownLatch startedLatch = new CountDownLatch(1);

        static void reset() {
            startedLatch = new CountDownLatch(1);
        }

        static boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return startedLatch.await(timeout, unit);
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("role", "assistant", "content", "sync"),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
            startedLatch.countDown();
            while (!observer.isCancelled()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
            observer.onComplete();
        }
    }
}
