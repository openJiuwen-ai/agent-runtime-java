/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Two real Runtime HTTP servers: outbound push negotiation through named callback to automatic parent resume. */
class HostedPushJourneyTest {
    private static final String CALLBACK = "/a2a/push-notifications/callback";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(60)
    void bothInstancesNegotiateOwnCallbackAndResumeOwnParentThroughRealRemoteRuntime() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        try (var caller = new SpringApplicationBuilder(CallerApplication.class).web(WebApplicationType.SERVLET)
                .registerShutdownHook(false).properties("server.port=" + port,
                        "spring.application.name=hosted-push-caller", "logging.level.root=WARN",
                        "openjiuwen.service.a2a.public-url=http://127.0.0.1:" + port,
                        "openjiuwen.service.a2a.push-notifications=true").run();
                var callee = new SpringApplicationBuilder(CalleeApplication.class).web(WebApplicationType.SERVLET)
                        .registerShutdownHook(false).properties("server.port=0",
                                "spring.application.name=hosted-push-callee", "logging.level.root=WARN",
                                "openjiuwen.service.a2a.push-notifications=true").run()) {
            var remote = callee.getBean(RemoteHandler.class);
            try {
                verifyJourney(caller, callee, port, remote);
            } finally {
                remote.release.countDown();
            }
        }
    }

    private void verifyJourney(ConfigurableApplicationContext caller, ConfigurableApplicationContext callee,
            int port, RemoteHandler remote) throws Exception {
        int calleePort = callee.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String url = "http://127.0.0.1:" + calleePort + "/a2a";
        var card = AgentCard.builder().name("remote").description("callback remote").version("1.0")
                .capabilities(new AgentCapabilities(false, true, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0")))
                .url(url).preferredTransport("JSONRPC").build();
        caller.getBean(A2ARemoteAgentCardRegistry.class).register("remote", card, 10, false);
        var client = HttpClient.newHttpClient();
        String contextId = "same-context-" + UUID.randomUUID();
        var first = start(client, port, "a", contextId);
        var second = start(client, port, "b", contextId);
        assertThat(remote.entered.await(10, TimeUnit.SECONDS)).isTrue();
        String firstTask = taskId(first.get(10, TimeUnit.SECONDS));
        String secondTask = taskId(second.get(10, TimeUnit.SECONDS));
        var catalog = caller.getBean(HostedRuntimeCatalog.class);
        assertThat(catalog.resolve("a").taskStore().get(firstTask).status().state())
                .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(catalog.resolve("b").taskStore().get(secondTask).status().state())
                .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        remote.release.countDown();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertCompleted(catalog, "a", firstTask, contextId);
            assertCompleted(catalog, "b", secondTask, contextId);
        });
        assertThat(caller.getBean(CallbackCapture.class).requests).contains(
                CALLBACK + "/a|Bearer test-push-token", CALLBACK + "/b|Bearer test-push-token");
        assertThat(remote.requests).hasSize(2);
        for (var request : remote.requests) {
            assertThat(request.lastUserParts()).anySatisfy(part -> {
                assertThat(part.get("kind")).isEqualTo("data");
                assertThat(part.get("data")).isEqualTo(Map.of("unchanged", "attachment"));
            });
        }
        assertThat(((CallerHandler) catalog.resolve("a").handler()).resumes).containsExactly(firstTask);
        assertThat(((CallerHandler) catalog.resolve("b").handler()).resumes).containsExactly(secondTask);
    }

    private static void assertCompleted(HostedRuntimeCatalog catalog, String id, String taskId, String contextId) {
        var task = catalog.resolve(id).taskStore().get(taskId);
        assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(task.contextId()).isEqualTo(contextId);
        assertThat(A2aPartContent.extractTaskResult(task)).contains("resumed-" + id, "remote-result:target-" + id)
                .doesNotContain("resumed-" + (id.equals("a") ? "b" : "a"));
        assertThat(catalog.resolve(id.equals("a") ? "b" : "a").taskStore().get(taskId)).isNull();
    }

    private static CompletableFuture<HttpResponse<String>> start(HttpClient client, int port, String id,
            String contextId) throws Exception {
        Map<String, Object> metadata = Map.of("runtime.a2a.callbackUrl",
                "http://127.0.0.1:" + port + CALLBACK, "runtime.a2a.callbackId", "same-notification-config",
                "runtime.a2a.callbackToken", "test-push-token");
        var body = Map.of("jsonrpc", "2.0", "id", id, "method", "SendMessage", "params",
                Map.of("metadata", metadata, "message", Map.of("role", "ROLE_USER", "messageId", id,
                        "contextId", contextId, "parts", List.of(Map.of("text", "start")))));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/a2a/agents/" + id))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String taskId(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var body = JSON.readTree(response.body());
        assertThat(body.has("error")).as(response.body()).isFalse();
        String task = body.path("result").path("task").path("id").asText();
        assertThat(task).isNotBlank();
        return task;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CallerApplication {
        @Bean
        HostedAgentDefinitions definitions() {
            return HostedAgentDefinitions.builder().add("a", new CallerHandler("a"))
                    .add("b", new CallerHandler("b")).build();
        }

        @Bean
        CallbackCapture callbackCapture() {
            return new CallbackCapture();
        }

        @Bean
        FilterRegistrationBean<Filter> capture(CallbackCapture capture) {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                capture.requests.add(http.getRequestURI() + "|" + http.getHeader("Authorization"));
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns(CALLBACK + "/*");
            return registration;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CalleeApplication {
        @Bean
        RemoteHandler remoteHandler() {
            return new RemoteHandler();
        }
    }

    static final class CallbackCapture {
        private final List<String> requests = new CopyOnWriteArrayList<>();
    }

    static final class CallerHandler implements AgentHandler {
        private final String id;
        private final List<String> resumes = new CopyOnWriteArrayList<>();

        CallerHandler(String id) {
            this.id = id;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            if (request.getMetadata().get("runtime.remoteToolResults") instanceof Map<?, ?> results) {
                resumes.add(String.valueOf(request.getMetadata().get("runtime.parentTaskId")));
                return response(request, "resumed-" + id + ":" + results.get("same-call"));
            }
            return new QueryResponse(Map.of("_interrupt", Map.of("type", "__interaction__",
                    "toolCallId", "same-call", "toolName", "remote", "message", "target-" + id,
                    "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "remote",
                            "parts", List.of(Map.of("kind", "data", "data", Map.of("unchanged", "attachment")))))),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
            observer.onComplete();
        }
    }

    static final class RemoteHandler implements AgentHandler {
        private final CountDownLatch entered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<ServeRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public QueryResponse query(ServeRequest request) {
            requests.add(request);
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Remote test release timed out");
                }
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException("Remote test interrupted", interrupted);
            }
            return response(request, "remote-result:" + request.lastUserQuery());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
            observer.onComplete();
        }
    }

    private static QueryResponse response(ServeRequest request, String text) {
        return new QueryResponse(Map.of("role", "assistant", "content", text), request.getConversationId());
    }
}
