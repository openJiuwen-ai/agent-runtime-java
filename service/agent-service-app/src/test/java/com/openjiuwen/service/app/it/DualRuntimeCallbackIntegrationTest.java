/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.it.DualRuntimeCallbackIntegrationTest.CallerRuntimeApplication;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Dual-runtime happy path for A2A callback-mode remote invocation.
 */
@SpringBootTest(classes = CallerRuntimeApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.application.name=caller-it", "openjiuwen.service.a2a.push-notifications=true"})
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DualRuntimeCallbackIntegrationTest {
    private static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";

    private static final String CALLBACK_ID_METADATA = "runtime.a2a.callbackId";

    private static final String RAW_TEXT = "callback-parts-raw-payload";

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

    @BeforeEach
    void startCallee() {
        callee = new SpringApplicationBuilder(CalleeRuntimeApplication.class).properties("server.port=0",
                "spring.application.name=callee-it", "openjiuwen.service.a2a.push-notifications=true").run();
        registry.register("callee", card(calleePort()), 5, false);
    }

    @AfterEach
    void stopCallee() {
        if (callee != null) {
            callee.close();
        }
    }

    @Test
    void callerDelegatesToCalleeWaitsForCallbackThenResumesOriginalTask() throws Exception {
        String callbackUrl = "http://127.0.0.1:" + callerPort + "/a2a/push-notifications/callback";
        Map<String, Object> firstBody = json(postA2a(rpc("SendMessage", "dual-runtime-start", Map.of("metadata",
                Map.of(CALLBACK_URL_METADATA, callbackUrl, CALLBACK_ID_METADATA, "push-dual-runtime"), "message",
                Map.of("role", "ROLE_USER", "messageId", "msg-dual-runtime-start", "contextId", "ctx-dual-runtime",
                        "parts", List.of(Map.of("kind", "text", "text", "start dual runtime")))))));
        Map<String, Object> waitingTask = taskFrom(firstBody);
        String taskId = String.valueOf(waitingTask.get("id"));

        Task completedTask = awaitCompletedTask(taskId);

        assertThat(completedTask.id()).isEqualTo(taskId);
        assertThat(completedTask.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(A2aPartContent.extractTaskResult(completedTask)).contains("caller resumed")
                .contains("callee result:delegate:start dual runtime");
        assertThat(completedTask.history())
                .allSatisfy(message -> assertThat(A2aPartContent.extract(message.parts())).doesNotContain("continue"));

        // FEAT-036 §3.4: callback mode + multimodal parts — the delegate payload survives
        // the push-notification round trip and the callee business handler receives the
        // full normalized parts (leading delegate text + url/raw/data in order).
        assertThat(DelayedCalleeHandler.capturedRequests()).hasSize(1);
        ServeRequest remoteRequest = DelayedCalleeHandler.capturedRequests().peek();
        List<Map<String, Object>> deliveredParts = remoteRequest.lastUserParts();
        assertThat(deliveredParts).extracting(part -> part.get("kind")).containsExactly("text", "url", "raw", "data");
        assertThat(deliveredParts.get(0).get("text")).isEqualTo("delegate:start dual runtime");

        Map<String, Object> urlPart = deliveredParts.get(1);
        assertThat(urlPart.get("url")).isEqualTo("https://example.com/attachments/id-card.png");
        assertThat(urlPart.get("filename")).isEqualTo("id-card.png");
        assertThat(urlPart.get("mediaType")).isEqualTo("image/png");

        Map<String, Object> rawPart = deliveredParts.get(2);
        assertThat(rawPart.get("bytesBase64"))
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(RAW_TEXT.getBytes()));
        assertThat(rawPart.get("filename")).isEqualTo("note.txt");
        assertThat(rawPart.get("mediaType")).isEqualTo("text/plain");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) deliveredParts.get(3).get("data");
        assertThat(data.get("orderId")).isEqualTo("A-1024");
        assertThat(data.get("vip")).isEqualTo(true);
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
        throw new AssertionError(
                "parent task did not complete automatically: " + taskId + ", lastObserved=" + lastObserved);
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

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
        return AgentCard.builder().name("callee").description("callee").provider(new AgentProvider("", ""))
                .version("1.0").capabilities(new AgentCapabilities(false, true, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Collections.emptyMap()).securityRequirements(List.of())
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
            return new CallerHandler();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.openjiuwen.service.app.controller", "com.openjiuwen.service.app.lifecycle"})
    static class CalleeRuntimeApplication {
        @Bean
        @Primary
        AgentHandler calleeHandler() {
            return new DelayedCalleeHandler();
        }
    }

    private static final class CallerHandler implements AgentHandler {
        @Override
        public QueryResponse query(ServeRequest request) {
            Object results = request.getMetadata().get("runtime.remoteToolResults");
            if (results instanceof Map<?, ?> remoteResults) {
                return response(request, "caller resumed:" + remoteResults.get("call-callee"));
            }
            // FEAT-036 §4.5: delegate interrupt carries tool attachments as normalized
            // parts alongside the message text.
            java.util.List<Map<String, Object>> attachments = List.of(
                    Map.of("kind", "url", "url", "https://example.com/attachments/id-card.png", "filename",
                            "id-card.png", "mediaType", "image/png"),
                    Map.of("kind", "raw", "bytesBase64",
                            java.util.Base64.getEncoder().encodeToString(RAW_TEXT.getBytes()), "filename", "note.txt",
                            "mediaType", "text/plain"),
                    Map.of("kind", "data", "data", Map.of("orderId", "A-1024", "vip", true)));
            return new QueryResponse(
                    Map.of("role", "assistant", "_interrupt",
                            Map.of("batchId", "dual-runtime-batch", "items", List.of(Map.of("index", 0, "toolCallId",
                                    "call-callee", "toolName", "callee-tool", "message",
                                    "delegate:" + request.lastUserQuery(), "context", Map.of("_interrupt_kind",
                                            "a2a_delegate", "agentName", "callee", "parts", attachments))))),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "unused"));
            observer.onComplete();
        }
    }

    private static final class DelayedCalleeHandler implements AgentHandler {
        private static final java.util.concurrent.ConcurrentLinkedQueue<ServeRequest> CAPTURED = new java.util.concurrent.ConcurrentLinkedQueue<>();

        static java.util.Queue<ServeRequest> capturedRequests() {
            return CAPTURED;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            CAPTURED.add(request);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                throw new IllegalStateException("callee sleep interrupted", ex);
            }
            return response(request, "callee result:" + request.lastUserQuery());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            QueryResponse response = query(request);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, response.getResult()));
            observer.onComplete();
        }

    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content), request.getConversationId());
    }
}
