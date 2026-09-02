/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

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

/**
 * Dual-runtime integration for delegate parts forwarding (design FEAT-036 mode
 * B):
 * caller tool attachments travel through the a2a_delegate interrupt, outbound
 * wire,
 * and are delivered to the callee handler as normalized message parts.
 */
@SpringBootTest(classes = DualRuntimePartsIntegrationTest.CallerRuntimeApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.application.name=caller-parts-it"})
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DualRuntimePartsIntegrationTest {
    private static final String RAW_TEXT = "raw-attachment-payload-1.0.0";

    private static final String RAW_BASE64 = Base64.getEncoder()
            .encodeToString(RAW_TEXT.getBytes(StandardCharsets.UTF_8));

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private A2ARemoteAgentCardRegistry registry;

    @Autowired
    private org.a2aproject.sdk.server.tasks.TaskStore taskStore;

    @LocalServerPort
    private int callerPort;

    private final ObjectMapper mapper = new ObjectMapper();

    private ConfigurableApplicationContext callee;

    @BeforeEach
    void startCallee() {
        CapturingCalleeHandler.receivedRequests().clear();
        callee = new SpringApplicationBuilder(CalleeRuntimeApplication.class)
                .properties("server.port=0", "spring.application.name=callee-parts-it").run();
        registry.register("callee", card(calleePort()), 5, false);
    }

    @AfterEach
    void stopCallee() {
        if (callee != null) {
            callee.close();
        }
    }

    @Test
    void callerDelegatesMultimodalPartsToCallee() throws Exception {
        // caller first message: plain text; handler raises a2a_delegate interrupt
        // whose context carries tool attachments as normalized parts (FEAT-036 §5.2)
        ResponseEntity<String> first = postA2a(rpc("SendMessage", "parts-delegate-start",
                Map.of("message", Map.of("role", "ROLE_USER", "messageId", "msg-parts-start", "contextId",
                        "ctx-parts-delegate", "parts", List.of(Map.of("text", "start parts delegate"))))));

        Task completed = awaitCompletedTask(taskId(first));

        assertThat(completed.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(CapturingCalleeHandler.receivedRequests()).hasSize(1);

        ServeRequest remoteRequest = CapturingCalleeHandler.receivedRequests().peek();
        Map<String, Object> userMessage = remoteRequest.getMessages().get(0);
        assertThat(userMessage.get("content")).isEqualTo("start parts delegate");
        assertThat(userMessage.get("parts")).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deliveredParts = (List<Map<String, Object>>) userMessage.get("parts");
        // design FEAT-036 §5.4: the delegate message text is the leading part,
        // normalized non-text attachments follow in order
        assertThat(deliveredParts).extracting(part -> part.get("kind")).containsExactly("text", "url", "raw", "data");
        assertThat(deliveredParts.get(0).get("text")).isEqualTo("start parts delegate");

        Map<String, Object> urlPart = deliveredParts.get(1);
        assertThat(urlPart.get("url")).isEqualTo("https://example.com/attachments/id-card.png");
        assertThat(urlPart.get("mediaType")).isEqualTo("image/png");
        assertThat(urlPart.get("filename")).isEqualTo("id-card.png");

        Map<String, Object> rawPart = deliveredParts.get(2);
        assertThat(rawPart.get("bytesBase64")).isEqualTo(RAW_BASE64);
        assertThat(new String(Base64.getDecoder().decode(assertInstanceOf(String.class, rawPart.get("bytesBase64"))),
                StandardCharsets.UTF_8)).isEqualTo(RAW_TEXT);
        assertThat(rawPart.get("mediaType")).isEqualTo("text/plain");
        assertThat(rawPart.get("filename")).isEqualTo("note.txt");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) deliveredParts.get(3).get("data");
        assertThat(data.get("orderId")).isEqualTo("A-1024");
        assertThat(data.get("vip")).isEqualTo(true);
    }

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/a2a/", new HttpEntity<>(toJson(body), headers), String.class);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String taskId(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = mapper.readValue(response.getBody(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).as("SendMessage must return a task").isNotNull();
        if (result.containsKey("task")) {
            return String.valueOf(((Map<?, ?>) result.get("task")).get("id"));
        }
        return String.valueOf(result.get("id"));
    }

    private Task awaitCompletedTask(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String lastObserved = "<missing>";
        while (System.currentTimeMillis() < deadline) {
            Task task = taskStore.get(taskId);
            if (task != null && task.status() != null
                    && task.status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
                return task;
            }
            lastObserved = task == null || task.status() == null ? "<missing>" : String.valueOf(task.status().state());
            Thread.sleep(100);
        }
        throw new AssertionError("parent task did not complete: " + taskId + ", lastObserved=" + lastObserved);
    }

    private static Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private int calleePort() {
        return callee.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private static AgentCard card(int port) {
        String url = "http://127.0.0.1:" + port + "/a2a";
        return AgentCard.builder().name("callee").description("callee").provider(new AgentProvider("", ""))
                .version("1.0").capabilities(new AgentCapabilities(false, false, false, List.of()))
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
            return new CapturingCalleeHandler();
        }
    }

    /**
     * Raises an a2a_delegate interrupt whose context carries tool attachments as
     * normalized parts (design FEAT-036 §4.5/§5.2), then resumes with the remote
     * result.
     */
    private static final class CallerHandler implements AgentHandler {
        @Override
        public QueryResponse query(ServeRequest request) {
            Object results = request.getMetadata().get("runtime.remoteToolResults");
            if (results instanceof Map<?, ?> remoteResults) {
                return response(request, "caller resumed:" + remoteResults.get("call-callee"));
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("_interrupt_kind", "a2a_delegate");
            context.put("agentName", "callee");
            List<Map<String, Object>> attachments = new ArrayList<>();
            attachments.add(Map.of("kind", "url", "url", "https://example.com/attachments/id-card.png", "filename",
                    "id-card.png", "mediaType", "image/png"));
            attachments.add(Map.of("kind", "raw", "bytesBase64", RAW_BASE64, "filename", "note.txt", "mediaType",
                    "text/plain"));
            attachments.add(Map.of("kind", "data", "data", Map.of("orderId", "A-1024", "vip", true)));
            context.put("parts", attachments);
            return new QueryResponse(
                    Map.of("role", "assistant", "_interrupt",
                            Map.of("batchId", "parts-delegate-batch", "items",
                                    List.of(Map.of("index", 0, "toolCallId", "call-callee", "toolName", "callee-tool",
                                            "message", "start parts delegate", "context", context)))),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new com.openjiuwen.service.spec.dto.QueryChunk(
                    com.openjiuwen.service.spec.dto.QueryChunk.TYPE_CHUNK, "unused"));
            observer.onComplete();
        }
    }

    /**
     * Captures inbound ServeRequests so the test can assert the delivered parts.
     */
    private static final class CapturingCalleeHandler implements AgentHandler {
        private static final ConcurrentLinkedQueue<ServeRequest> RECEIVED = new ConcurrentLinkedQueue<>();

        static ConcurrentLinkedQueue<ServeRequest> receivedRequests() {
            return RECEIVED;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            RECEIVED.add(request);
            return response(request, "callee received parts");
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            QueryResponse response = query(request);
            observer.onNext(new com.openjiuwen.service.spec.dto.QueryChunk(
                    com.openjiuwen.service.spec.dto.QueryChunk.TYPE_CHUNK, response.getResult()));
            observer.onComplete();
        }
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content), request.getConversationId());
    }
}
