/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Dual-runtime degradation path for A2A callback-mode remote failures.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"spring.application.name=caller-failure-it",
        "openjiuwen.service.a2a.push-notifications=true"})
@ContextConfiguration(classes = DualRuntimeFailureIntegrationTest.CallerRuntimeApplication.class)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DualRuntimeFailureIntegrationTest {
    private static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";

    private static final String CALLBACK_ID_METADATA = "runtime.a2a.callbackId";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private A2ARemoteAgentCardRegistry registry;

    @Autowired
    private TaskStore taskStore;

    @LocalServerPort
    private int callerPort;

    private final ObjectMapper mapper = new ObjectMapper();

    private ConfigurableApplicationContext callee;

    private FailingCalleeHandler failingCallee;

    @BeforeEach
    void startCallee() {
        callee = new SpringApplicationBuilder(FailingCalleeRuntimeApplication.class).properties("server.port=0",
                "spring.application.name=callee-failure-it", "openjiuwen.service.a2a.push-notifications=true").run();
        failingCallee = callee.getBean(FailingCalleeHandler.class);
        registry.register("failing-callee", card(calleePort()), 5, false);
    }

    @AfterEach
    void stopCallee() {
        if (failingCallee != null) {
            failingCallee.releaseFailure();
        }
        if (callee != null) {
            callee.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerReceivesRemoteFailureCallbackAndResumesWithStructuredError() throws Exception {
        String callbackUrl = "http://127.0.0.1:" + callerPort + "/a2a/push-notifications/callback";
        Map<String, Object> firstBody = json(postA2a(rpc("SendMessage", "dual-runtime-failure-start",
                Map.of("metadata",
                        Map.of(CALLBACK_URL_METADATA, callbackUrl, CALLBACK_ID_METADATA, "push-dual-runtime-failure"),
                        "message",
                        Map.of("role", "ROLE_USER", "messageId", "msg-dual-runtime-failure-start", "contextId",
                                "ctx-dual-runtime-failure", "parts",
                                List.of(Map.of("kind", "text", "text", "start failing runtime")))))));
        Map<String, Object> waitingTask = taskFrom(firstBody);
        String taskId = String.valueOf(waitingTask.get("id"));

        assertThat(((Map<String, Object>) waitingTask.get("status")).get("state"))
                .isEqualTo("TASK_STATE_INPUT_REQUIRED");

        failingCallee.releaseFailure();
        Task completedTask = awaitCompletedTask(taskId);

        assertThat(completedTask.id()).isEqualTo(taskId);
        assertThat(completedTask.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(A2aPartContent.extractTaskResult(completedTask)).contains("caller degraded")
                .contains("REMOTE_BUSINESS_FAILURE")
                .contains("AGENT_EXECUTION_FAILED")
                .contains("remoteAgentId=failing-callee");
        assertThat(completedTask.history()).allSatisfy(message ->
                assertThat(A2aPartContent.extract(message.parts())).doesNotContain("continue"));
    }

    private Task awaitCompletedTask(String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String lastObserved = "";
        while (Instant.now().isBefore(deadline)) {
            Task task = taskStore.get(taskId);
            if (task != null && task.status() != null
                    && task.status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
                return task;
            }
            lastObserved = task == null || task.status() == null ? "<missing>" : String.valueOf(task.status().state());
            Thread.sleep(100);
        }
        throw new AssertionError("parent task did not complete automatically: " + taskId
                + ", lastObserved=" + lastObserved);
    }

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // String body so the client sets Content-Length; the /a2a pre-check rejects
        // chunked (missing Content-Length) requests with 413 (FEAT-036 §2.2).
        return rest.postForEntity("/a2a/", new HttpEntity<>(toJson(body), headers), String.class);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readValue(response.getBody(), Map.class);
    }

    private int calleePort() {
        return callee.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private static Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> taskFrom(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        if (result.containsKey("task")) {
            return (Map<String, Object>) result.get("task");
        }
        return result;
    }

    private static AgentCard card(int port) {
        String url = "http://127.0.0.1:" + port + "/a2a";
        return AgentCard.builder().name("failing-callee").description("failing callee")
                .provider(new AgentProvider("", "")).version("1.0")
                .capabilities(new AgentCapabilities(false, true, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Collections.emptyMap())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0"))).url(url)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.openjiuwen.service.app.controller", "com.openjiuwen.service.app.lifecycle"})
    static class CallerRuntimeApplication {
        @Bean
        @Primary
        AgentHandler callerHandler() {
            return new DegradingCallerHandler();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.openjiuwen.service.app.controller", "com.openjiuwen.service.app.lifecycle"})
    static class FailingCalleeRuntimeApplication {
        @Bean
        @Primary
        AgentHandler calleeHandler() {
            return new FailingCalleeHandler();
        }
    }

    private static final class DegradingCallerHandler implements AgentHandler {
        @Override
        public QueryResponse query(ServeRequest request) {
            Object results = request.getMetadata().get("runtime.remoteToolResults");
            if (results instanceof Map<?, ?> remoteResults) {
                return response(request, "caller degraded:" + remoteResults.get("call-failing-callee"));
            }
            return new QueryResponse(
                    Map.of("role", "assistant", "_interrupt", Map.of("batchId", "dual-runtime-failure-batch", "items",
                            List.of(Map.of("index", 0, "toolCallId", "call-failing-callee", "toolName",
                                    "failing-callee-tool", "message", "delegate:" + request.lastUserQuery(), "context",
                                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "failing-callee"))))),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "unused"));
            observer.onComplete();
        }
    }

    private static final class FailingCalleeHandler implements AgentHandler {
        private final CountDownLatch failureRelease = new CountDownLatch(1);

        @Override
        public QueryResponse query(ServeRequest request) {
            awaitFailureRelease();
            throw new UnsupportedOperationException("callee internal details");
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onError(new IllegalStateException("callee business failure"));
        }

        private void releaseFailure() {
            failureRelease.countDown();
        }

        private void awaitFailureRelease() {
            try {
                if (!failureRelease.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("callee failure release timed out");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException("callee failure release interrupted", e);
            }
        }
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content), request.getConversationId());
    }
}
