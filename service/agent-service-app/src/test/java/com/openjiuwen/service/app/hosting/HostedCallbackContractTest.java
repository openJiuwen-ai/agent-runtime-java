/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@SpringBootTest(classes = HostedCallbackContractTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.application.name=hosted-callback-test", "openjiuwen.service.a2a.push-notifications=true",
                "openjiuwen.service.a2a.agents.a.push-notifications=false",
                "openjiuwen.service.security.enabled=true", "openjiuwen.service.security.auth.enabled=true"})
/**
 * Verifies per-target callback authorization, deduplication and selection.
 *
 * @since 0.1.2
 */
@AutoConfigureTestRestTemplate
class HostedCallbackContractTest {
    private static final String CALLBACK = "/a2a/push-notifications/callback";
    private static final String TEST_TOKEN = "hosted-callback-test-token";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private HostedRuntimeCatalog catalog;
    @Autowired
    private CallbackAuthorizer authorizer;
    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void namedCallbackWithTokenResumesOnlyItsOwnParent() {
        String id = UUID.randomUUID().toString();
        var a = catalog.resolve("a");
        var b = catalog.resolve("b");
        seed(a, id, true);
        seed(b, id, true);
        for (HostedAgentRuntime target : List.of(b, a)) {
            String path = CALLBACK + "/" + target.agentId();
            target.execution().pushConfigStore().setInfo(TaskPushNotificationConfig.builder()
                    .id(id).taskId("remote-" + id).url("http://127.0.0.1:" + port + path).token(TEST_TOKEN).build());
            Task task = remoteTask(id, "result-" + target.agentId());
            target.execution().pushSender().sendNotification(TaskStatusUpdateEvent.builder()
                    .taskId(task.id()).contextId(task.contextId()).status(task.status()).build(), task);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Task parent = target.taskStore().get(id);
                assertThat(parent.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
                assertThat(parent.toString()).contains("result-" + target.agentId());
            });
            assertThat(authorizer.accepted).contains(path + "|Bearer " + TEST_TOKEN);
            if (target == b) {
                assertThat(a.taskStore().get(id).status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
                assertThat(snapshot(a, id).get("state")).isEqualTo("WAITING_INPUT");
            }
        }
        // The default Card disables push, but this must not gate the other target's callback.
        assertThat(rest.getForObject("/.well-known/agent-card.json", String.class))
                .contains("\"pushNotifications\":false");
    }

    @Test
    void notificationDeduplicationAndRollbackStayWithinTarget() throws Exception {
        String id = UUID.randomUUID().toString();
        var a = catalog.resolve("a");
        var b = catalog.resolve("b");
        seed(a, id, false);
        var body = callback(id, "result");
        assertThat(post(CALLBACK + "/b", body, true).getStatusCode().value()).isEqualTo(404);
        assertThat(snapshot(a, id).get("state")).isEqualTo("WAITING_INPUT");
        assertThat(post(CALLBACK + "/a", body, true).getStatusCode().value()).isEqualTo(200);
        seed(b, id, false);
        assertThat(post(CALLBACK + "/b", body, true).getStatusCode().value()).isEqualTo(200);
        assertThat(snapshot(b, id).get("state")).isEqualTo("READY_TO_RESUME");
        for (String target : List.of("a", "b")) {
            assertThat(post(CALLBACK + "/" + target, body, true).getStatusCode().value()).isEqualTo(200);
            assertThat(post(CALLBACK + "/" + target, callback(id, "changed"), true)
                    .getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    void defaultCallbackAndUnknownTargetNeverScanOtherStores() throws Exception {
        String id = UUID.randomUUID().toString();
        var a = catalog.resolve("a");
        var b = catalog.resolve("b");
        seed(a, id, false);
        seed(b, id, false);
        assertThat(post(CALLBACK + "/unknown", callback(id, "unknown"), true).getStatusCode().value())
                .isEqualTo(404);
        assertThat(snapshot(a, id).get("state")).isEqualTo("WAITING_INPUT");
        assertThat(snapshot(b, id).get("state")).isEqualTo("WAITING_INPUT");
        assertThat(post(CALLBACK, callback(id, "default"), true).getStatusCode().value()).isEqualTo(200);
        assertThat(snapshot(a, id).get("state")).isEqualTo("READY_TO_RESUME");
        assertThat(snapshot(b, id).get("state")).isEqualTo("WAITING_INPUT");
    }

    @Test
    void publishesSelectionOnceAndNeverForRejectedRequests() throws Exception {
        String id = UUID.randomUUID().toString();
        seed(catalog.resolve("a"), id, false);
        seed(catalog.resolve("b"), id, false);
        var body = callback(id, "result");
        assertThat(post(CALLBACK, body, true).getStatusCode().value()).isEqualTo(200);
        assertThat(post(CALLBACK + "/b", body, true).getStatusCode().value()).isEqualTo(200);
        assertThat(post(CALLBACK + "/unknown", body, true).getStatusCode().value()).isEqualTo(404);
        assertThat(post(CALLBACK + "/b", body, false).getStatusCode().value()).isEqualTo(403);
        assertThat(authorizer.selections.stream().filter(value -> value.startsWith(id + "|")))
                .containsExactly(id + "|a", id + "|b");
    }

    @Test
    void authorizationRunsBeforeInstanceValidationForNewIngressPaths() throws Exception {
        var body = callback(UUID.randomUUID().toString(), "denied");
        for (String target : List.of("a", "unknown", "bad!")) {
            assertThat(post(CALLBACK + "/" + target, body, false).getStatusCode().value()).isEqualTo(403);
            assertThat(post("/a2a/agents/" + target,
                    Map.of("jsonrpc", "2.0", "id", "denied", "method", "GetTask", "params", Map.of("id", "x")),
                    false).getStatusCode().value()).isEqualTo(403);
            var headers = new HttpHeaders();
            headers.set("X-User-ID", "deny");
            assertThat(rest.exchange("/a2a/agents/" + target + "/.well-known/agent-card.json",
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode().value()).isEqualTo(403);
        }
        var headers = new HttpHeaders();
        headers.set("X-User-ID", "deny");
        assertThat(rest.exchange("/a2a/agents", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(403);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body, boolean isAuthorized) throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", isAuthorized ? "allow" : "deny");
        if (body.get("notificationId") instanceof String notificationId) {
            headers.set("X-Selection-Test", notificationId);
        }
        if (isAuthorized) {
            headers.setBearerAuth(TEST_TOKEN);
        }
        return rest.postForEntity(path, new HttpEntity<>(mapper.writeValueAsBytes(body), headers), String.class);
    }

    private static void seed(HostedAgentRuntime target, String id, boolean shouldIncludeParent) {
        if (shouldIncludeParent) {
            target.taskStore().save(Task.builder().id(id).contextId("context-" + id)
                    .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED)).build(), true);
        }
        target.taskStore().save(Task.builder().id("shadow:" + target.agentId() + ":" + id)
                .contextId("context-" + id).status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                .metadata(Map.of("_remote_batch", Map.of("batchId", "batch-" + id, "parentTaskId", id,
                        "state", "WAITING_INPUT", "members", List.of(Map.of("index", 0, "toolCallId", "call",
                                "toolName", "remote-tool", "agentName", "remote", "state", "INPUT_REQUIRED",
                                "remoteTaskId", "remote-" + id, "resultCategory", "INPUT_REQUIRED",
                                "inputPrompt", "waiting"))))).build(), true);
    }

    private static Map<?, ?> snapshot(HostedAgentRuntime target, String id) {
        return (Map<?, ?>) target.taskStore().get("shadow:" + target.agentId() + ":" + id)
                .metadata().get("_remote_batch");
    }

    private static Map<String, Object> callback(String id, String result) {
        return Map.of("jsonrpc", "2.0", "notificationId", id, "result", Map.of("task", Map.of("id", "remote-" + id,
                "contextId", "context-" + id, "status", Map.of("state", "TASK_STATE_COMPLETED", "message",
                        Map.of("role", "ROLE_AGENT", "messageId", id, "parts", List.of(Map.of("text", result)))))));
    }

    private static Task remoteTask(String id, String result) {
        var message = Message.builder().role(Message.Role.ROLE_AGENT).messageId(id)
                .parts(List.of(new TextPart(result))).build();
        return Task.builder().id("remote-" + id).contextId("context-" + id)
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, message, null)).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean(destroyMethod = "")
        AgentHandler a() {
            return new ResumeHandler("a");
        }

        @Bean(destroyMethod = "")
        AgentHandler b() {
            return new ResumeHandler("b");
        }

        @Bean
        HostedAgentDefinitions definitions(@Qualifier("a") AgentHandler a, @Qualifier("b") AgentHandler b) {
            return HostedAgentDefinitions.builder().add("a", a).add("b", b).build();
        }

        @Bean
        CallbackAuthorizer callbackAuthorizer() {
            return new CallbackAuthorizer();
        }
    }

    static final class CallbackAuthorizer implements FineGrainedAuthorizer {
        private final List<String> accepted = new CopyOnWriteArrayList<>();
        private final List<String> selections = new CopyOnWriteArrayList<>();

        @Override
        public AuthorizationResult authorize(com.openjiuwen.service.spec.security.AuthorizationRequest input) {
            var request = assertInstanceOf(ServletRequestAttributes.class,
                    RequestContextHolder.currentRequestAttributes()).getRequest();
            if ("deny".equals(input.userId()) || "a2a-push-callback".equals(input.resource())
                    && !("Bearer " + TEST_TOKEN).equals(request.getHeader("Authorization"))) {
                return AuthorizationResult.deny("test policy denied");
            }
            accepted.add(request.getRequestURI() + "|" + request.getHeader("Authorization"));
            String selectionTest = request.getHeader("X-Selection-Test");
            if ("a2a-push-callback".equals(input.resource()) && selectionTest != null) {
                HostedIngressResolver.observeSelection(request,
                        target -> selections.add(selectionTest + "|" + target.agentId()));
            }
            return AuthorizationResult.allow();
        }
    }

    private record ResumeHandler(String id) implements AgentHandler {
        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("role", "assistant", "content",
                    id + ":" + request.getMetadata().get("runtime.remoteToolResults")), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
            observer.onComplete();
        }
    }
}
