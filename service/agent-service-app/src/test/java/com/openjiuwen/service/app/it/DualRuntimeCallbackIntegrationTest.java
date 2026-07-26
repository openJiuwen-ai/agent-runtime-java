/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual-runtime happy path for A2A callback-mode remote invocation.
 */
@SpringBootTest(classes = DualRuntimeCallbackIntegrationTest.CallerRuntimeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.application.name=caller-it",
        "openjiuwen.service.a2a.push-notifications=true",
        "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=127.0.0.1"
    })
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DualRuntimeCallbackIntegrationTest {
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

    @BeforeEach
    void startCallee() {
        callee = new SpringApplicationBuilder(CalleeRuntimeApplication.class)
            .properties(
                "server.port=0",
                "spring.application.name=callee-it",
                "openjiuwen.service.a2a.push-notifications=true",
                "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=127.0.0.1")
            .run();
        registry.register("callee", card(calleePort()), 5, false);
    }

    @AfterEach
    void stopCallee() {
        if (callee != null) {
            callee.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerDelegatesToCalleeWaitsForCallbackThenResumesOriginalTask() throws Exception {
        String callbackUrl = "http://127.0.0.1:" + callerPort + "/a2a/push-notifications/callback";
        Map<String, Object> firstBody = json(postA2a(rpc("SendMessage", "dual-runtime-start", Map.of(
            "metadata", Map.of(
                CALLBACK_URL_METADATA, callbackUrl,
                CALLBACK_ID_METADATA, "push-dual-runtime"),
            "message", Map.of(
                "role", "ROLE_USER",
                "messageId", "msg-dual-runtime-start",
                "contextId", "ctx-dual-runtime",
                "parts", List.of(Map.of("kind", "text", "text", "start dual runtime")))))));
        Map<String, Object> waitingTask = taskFrom(firstBody);
        String taskId = String.valueOf(waitingTask.get("id"));

        assertThat(((Map<String, Object>) waitingTask.get("status")).get("state"))
            .isEqualTo("TASK_STATE_INPUT_REQUIRED");

        Map<String, Object> readyBatch = awaitReadyRemoteBatch(taskId);
        List<Map<String, Object>> members = (List<Map<String, Object>>) readyBatch.get("members");
        assertThat(readyBatch).containsEntry("state", "READY_TO_RESUME");
        assertThat(members).singleElement().satisfies(member -> assertThat(member)
            .containsEntry("agentName", "callee")
            .containsEntry("state", "COMPLETED")
            .containsEntry("resultCategory", "COMPLETED"));
        assertThat(String.valueOf(members.get(0).get("result"))).contains("callee result:delegate:start dual runtime");

        Map<String, Object> resumedBody = json(postA2a(rpc("SendMessage", "dual-runtime-resume", Map.of(
            "message", Map.of(
                "role", "ROLE_USER",
                "messageId", "msg-dual-runtime-resume",
                "taskId", taskId,
                "contextId", "ctx-dual-runtime",
                "parts", List.of(Map.of("kind", "text", "text", "continue")))))));
        Map<String, Object> completedTask = taskFrom(resumedBody);

        assertThat(completedTask.get("id")).isEqualTo(taskId);
        assertThat(((Map<String, Object>) completedTask.get("status")).get("state"))
            .isEqualTo("TASK_STATE_COMPLETED");
        assertThat(allArtifactText(completedTask))
            .contains("caller resumed")
            .contains("callee result:delegate:start dual runtime");
    }

    private Map<String, Object> awaitReadyRemoteBatch(String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        String shadowTaskId = "shadow:caller-it:" + taskId;
        String lastObserved = "";
        while (Instant.now().isBefore(deadline)) {
            Task shadow = taskStore.get(shadowTaskId);
            if (shadow != null && shadow.metadata() != null
                    && shadow.metadata().get("_remote_batch") instanceof Map<?, ?> batch
                    && isCompletedBatch(batch)) {
                Map<String, Object> result = new LinkedHashMap<>();
                batch.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            lastObserved = shadow == null ? "<missing>" : String.valueOf(shadow.metadata());
            Thread.sleep(100);
        }
        throw new AssertionError("remote batch was not recovered for " + shadowTaskId
            + ", lastObserved=" + lastObserved);
    }

    private static boolean isCompletedBatch(Map<?, ?> batch) {
        if (!"READY_TO_RESUME".equals(batch.get("state")) || !(batch.get("members") instanceof List<?> members)) {
            return false;
        }
        return members.stream().allMatch(member -> member instanceof Map<?, ?> item
                && "COMPLETED".equals(item.get("state")));
    }

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/a2a/", new HttpEntity<>(body, headers), String.class);
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

    @SuppressWarnings("unchecked")
    private static String allArtifactText(Map<String, Object> task) {
        var artifacts = (List<Map<String, Object>>) task.get("artifacts");
        if (artifacts == null || artifacts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> artifact : artifacts) {
            var parts = (List<Map<String, Object>>) artifact.get("parts");
            if (parts == null) {
                continue;
            }
            for (Map<String, Object> part : parts) {
                Object value = part.get("text");
                if (value instanceof String item) {
                    text.append(item);
                }
            }
        }
        return text.toString();
    }

    private static AgentCard card(int port) {
        String url = "http://127.0.0.1:" + port + "/a2a";
        return AgentCard.builder()
            .name("callee")
            .description("callee")
            .provider(new AgentProvider("", ""))
            .version("1.0")
            .capabilities(new AgentCapabilities(false, true, false, List.of()))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .skills(List.of())
            .securitySchemes(Collections.emptyMap())
            .securityRequirements(List.of())
            .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0")))
            .url(url)
            .preferredTransport("JSONRPC")
            .additionalInterfaces(List.of())
            .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
        "com.openjiuwen.service.app.controller",
        "com.openjiuwen.service.app.lifecycle"
    })
    static class CallerRuntimeApplication {
        @Bean
        @Primary
        AgentHandler callerHandler() {
            return new CallerHandler();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
        "com.openjiuwen.service.app.controller",
        "com.openjiuwen.service.app.lifecycle"
    })
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
            return new QueryResponse(Map.of(
                "role", "assistant",
                "_interrupt", Map.of(
                    "batchId", "dual-runtime-batch",
                    "items", List.of(Map.of(
                        "index", 0,
                        "toolCallId", "call-callee",
                        "toolName", "callee-tool",
                        "message", "delegate:" + request.lastUserQuery(),
                        "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "callee"))))),
                request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "unused"));
            observer.onComplete();
        }
    }

    private static final class DelayedCalleeHandler implements AgentHandler {
        @Override
        public QueryResponse query(ServeRequest request) {
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
