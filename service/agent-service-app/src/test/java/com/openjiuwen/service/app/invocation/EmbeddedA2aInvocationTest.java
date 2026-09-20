/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.hosting.HostedRuntimeCatalog;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class EmbeddedA2aInvocationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hostKeepsItsPortWhileRuntimeMappingsAndIngressSecurityDisappear() throws Exception {
        try (var context = start(WebApplicationType.SERVLET, SingleApplication.class, WideScan.class)) {
            var web = (ServletWebServerApplicationContext) context;
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + web.getWebServer().getPort();
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/customer")).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("customer-ok");
            var mapping = context.getBean(RequestMappingHandlerMapping.class);
            assertThat(mapping.getHandlerMethods().values()).noneMatch(method ->
                    method.getBeanType().getName().startsWith("com.openjiuwen.service.app.controller"));
            for (String path : List.of("/a2a", "/a2a/", "/a2a/agents/a", "/v1/query", "/query",
                    "/v1/query/reactive", "/v1/reset_conversation", "/reset_conversation", "/health",
                    "/v1/current_active_tasks", "/.well-known/agent-card.json", "/.well-known/agent.json",
                    "/a2a/agents", "/a2a/agents/a/.well-known/agent-card.json",
                    "/a2a/push-notifications/callback", "/a2a/push-notifications/callback/a")) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).as(path).isEqualTo(404);
            }
            for (String name : List.of("tlsWebServerCustomizer", "resourceAuthorizationAspect",
                    "authorizationDeniedExceptionHandler", "fineGrainedAuthorizerBootstrapValidator")) {
                assertThat(context.containsBean(name)).as(name).isFalse();
            }
            assertThat(context.getBean(A2aRuntimeInvoker.class)).isNotNull();
            taskJourney(context, null);
        }
    }

    @Test
    void noWebTaskJourneyAndShutdownRejection() throws Exception {
        A2aRuntimeInvoker invoker;
        try (var context = start(WebApplicationType.NONE, SingleApplication.class)) {
            invoker = context.getBean(A2aRuntimeInvoker.class);
            taskJourney(context, null);
        }
        JsonNode result = one(invoker.invoke(new A2aInvocationRequest(null, rpc("GetTask", "missing", null, ""), null)));
        assertThat(result.path("error").path("message").asText()).isEqualTo("Agent is not ready");
        assertThat(result.path("id").asText()).isEqualTo("rpc-id");
    }

    @Test
    void hostedTargetsKeepIndependentHandlersAndTaskStores() throws Exception {
        try (var context = start(WebApplicationType.NONE, HostedApplication.class)) {
            var invoker = context.getBean(A2aRuntimeInvoker.class);
            var catalog = context.getBean(HostedRuntimeCatalog.class);
            for (String agent : List.of("a", "b")) {
                JsonNode response = one(invoker.invoke(new A2aInvocationRequest(agent,
                        rpc("SendMessage", null, UUID.randomUUID().toString(), "hello"), null)));
                JsonNode task = response.path("result").path("task");
                assertThat(task.toString()).contains(agent + ":hello");
                String id = task.path("id").asText();
                assertThat(catalog.resolve(agent).taskStore().get(id)).isNotNull();
                String other = agent.equals("a") ? "b" : "a";
                assertThat(one(invoker.invoke(new A2aInvocationRequest(other,
                        rpc("GetTask", id, null, ""), null))).has("error")).isTrue();
            }
            assertThat(one(invoker.invoke(new A2aInvocationRequest(null,
                    rpc("SendMessage", null, "default", "hello"), null))).toString()).contains("b:hello");
            taskJourney(context, "a");
        }
    }

    @Test
    void earlyReturnStreamingSubmissionCancellationAndResubscriptionPreserveExecution() throws Exception {
        try (var context = start(WebApplicationType.NONE, SingleApplication.class)) {
            var invoker = context.getBean(A2aRuntimeInvoker.class);
            var handler = context.getBean(ControlledHandler.class);
            var store = context.getBean(TaskStore.class);
            handler.entered = new CountDownLatch(1);
            handler.release = new CountDownLatch(1);
            String body = rpc("SendMessage", null, "early", "block");
            var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body);
            ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("params"))
                    .putObject("configuration").put("returnImmediately", true);
            JsonNode early = one(invoker.invoke(new A2aInvocationRequest(null, envelope.toString(), null)))
                    .path("result").path("task");
            assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
            String id = early.path("id").asText();
            assertThat(id).isNotEmpty();
            assertThat(early.path("status").path("state").asText()).isNotEqualTo("TASK_STATE_COMPLETED");
            int calls = handler.calls.get();
            var observed = invoker.invoke(new A2aInvocationRequest(null, rpc("SubscribeToTask", id, null, ""), null));
            var probe = new CancellingProbe();
            CompletableFuture.runAsync(() -> observed.subscribe(probe));
            assertThat(probe.received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.calls).hasValue(calls);
            assertThat(store.get(id).status().state().name()).isNotEqualTo("TASK_STATE_CANCELED");
            handler.release.countDown();
            awaitCompleted(store, id);

            handler.entered = new CountDownLatch(1);
            handler.release = new CountDownLatch(1);
            var streaming = invoker.invoke(new A2aInvocationRequest(null,
                    rpc("SendStreamingMessage", null, "unsubscribed", "block"), null));
            assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.calls).hasValue(calls + 1);
            var cancelled = new CancellingProbe();
            CompletableFuture.runAsync(() -> streaming.subscribe(cancelled));
            assertThat(cancelled.received.await(5, TimeUnit.SECONDS)).isTrue();
            JsonNode firstEvent = JSON.readTree(cancelled.first).path("result");
            String streamingId = firstEvent.has("task") ? firstEvent.path("task").path("id").asText()
                    : firstEvent.path("statusUpdate").path("taskId").asText();
            if (streamingId.isEmpty()) {
                streamingId = JSON.readTree(cancelled.first).path("result").path("taskId").asText();
            }
            assertThat(streamingId).isNotEmpty();
            handler.release.countDown();
            awaitCompleted(store, streamingId);
            assertThat(handler.calls).hasValue(calls + 1);
        }
    }

    private static void awaitCompleted(TaskStore store, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (store.get(id) != null && store.get(id).status().state().name().equals("TASK_STATE_COMPLETED")) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task did not complete after observation cancellation: " + id);
    }

    static final class CancellingProbe implements Flow.Subscriber<String> {
        final CountDownLatch received = new CountDownLatch(1);
        Flow.Subscription subscription;
        volatile String first;
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(String value) { first = value; subscription.cancel(); received.countDown(); }
        public void onError(Throwable error) { received.countDown(); }
        public void onComplete() { }
    }

    @Test
    void concurrentIdentityAndBusinessMetadataRemainRequestLocal() throws Exception {
        try (var context = start(WebApplicationType.NONE, SingleApplication.class)) {
            var invoker = context.getBean(A2aRuntimeInvoker.class);
            var handler = context.getBean(ControlledHandler.class);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String user = "user-" + i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode)
                                JSON.readTree(rpc("SendMessage", null, user, "hello"));
                        var params = (com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("params");
                        params.putObject("metadata").put("business", user);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) params.path("message"))
                                .putObject("metadata").put("message-meta", user);
                        one(invoker.invoke(new A2aInvocationRequest(null, envelope.toString(),
                                Map.of("X-USER-ID", user, "x-space-id", "space-" + user, "x-tenant-id", "  ",
                                        "Authorization", "secret-" + user))));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 8; i++) {
                String user = "user-" + i;
                ServeRequest received = handler.requests.get(user);
                assertThat(received.getUserId()).isEqualTo(user);
                assertThat(received.getSpaceId()).isEqualTo("space-" + user);
                assertThat(received.getTenantId()).isNull();
                assertThat(received.getMetadata()).containsEntry("business", user);
                assertThat(received.getMessages().get(0).get("metadata")).isEqualTo(Map.of("message-meta", user));
            }
        }
    }

    private static void taskJourney(ConfigurableApplicationContext context, String agent) throws Exception {
        A2aRuntimeInvoker invoker = context.getBean(A2aRuntimeInvoker.class);
        String conversation = UUID.randomUUID().toString();
        Map<String, String> headers = new LinkedHashMap<>(Map.of("X-USER-ID", "alice", "x-space-id", "space",
                "x-tenant-id", "tenant", "Authorization", "must-not-propagate"));
        var request = new A2aInvocationRequest(agent, rpc("SendMessage", null, conversation, "wait"), headers);
        headers.put("X-USER-ID", "changed");
        JsonNode waiting = one(invoker.invoke(request)).path("result").path("task");
        String taskId = waiting.path("id").asText();
        assertThat(taskId).isNotEmpty();
        assertThat(waiting.path("status").path("state").asText()).isEqualTo("TASK_STATE_INPUT_REQUIRED");
        JsonNode query = one(invoker.invoke(new A2aInvocationRequest(agent,
                rpc("GetTask", taskId, null, ""), null))).path("result");
        assertThat(query.path("id").asText()).isEqualTo(taskId);
        var handler = agent == null ? context.getBean(ControlledHandler.class)
                : (ControlledHandler) context.getBean(HostedRuntimeCatalog.class).resolve(agent).handler();
        assertThat(handler.last.getUserId()).isEqualTo("alice");
        assertThat(handler.last.getSpaceId()).isEqualTo("space");
        assertThat(handler.last.getTenantId()).isEqualTo("tenant");
        assertThat(handler.last.toString()).doesNotContain("must-not-propagate");
        JsonNode completed = one(invoker.invoke(new A2aInvocationRequest(agent,
                rpc("SendMessage", taskId, conversation, "answer"), null))).path("result").path("task");
        assertThat(completed.path("id").asText()).isEqualTo(taskId);
        assertThat(completed.path("status").path("state").asText()).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(handler.last.getMetadata()).containsKey("_interrupt");
        int before = handler.calls.get();
        var publisher = invoker.invoke(new A2aInvocationRequest(agent,
                rpc("SendStreamingMessage", null, UUID.randomUUID().toString(), "stream"), null));
        var frames = collect(publisher);
        assertThat(frames).anyMatch(frame -> frame.contains("artifactUpdate"));
        assertThat(frames).anyMatch(frame -> frame.contains("TASK_STATE_COMPLETED"));
        assertThat(frames).allMatch(frame -> !frame.startsWith("data:") && !frame.startsWith("event:"));
        for (String frame : frames) { assertThat(JSON.readTree(frame).path("id").asText()).isEqualTo("rpc-id"); }
        assertThat(handler.calls).hasValue(before + 1);
        InvocationPublisherTest.Probe second = new InvocationPublisherTest.Probe();
        publisher.subscribe(second);
        assertThat(second.error).isInstanceOf(IllegalStateException.class);
        assertThat(handler.calls).hasValue(before + 1);
    }

    static ConfigurableApplicationContext start(WebApplicationType web, Class<?>... sources) {
        return new SpringApplicationBuilder(sources).web(web).properties(
                "server.port=0", "spring.application.name=embedded-invocation-test",
                "openjiuwen.service.http.enabled=false", "openjiuwen.service.security.enabled=true",
                "openjiuwen.service.security.auth.enabled=true", "openjiuwen.service.security.tls.enabled=true",
                "openjiuwen.service.security.tls.key-store=missing-keystore", "spring.main.banner-mode=off").run();
    }

    static String rpc(String method, String task, String conversation, String text) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (method.equals("GetTask") || method.equals("SubscribeToTask")) {
            params.put("id", task);
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "ROLE_USER");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("contextId", conversation);
            message.put("parts", List.of(Map.of("text", text)));
            if (task != null) { message.put("taskId", task); }
            params.put("message", message);
        }
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "rpc-id", "method", method, "params", params));
    }

    static JsonNode one(Flow.Publisher<String> publisher) throws Exception {
        List<String> values = collect(publisher);
        assertThat(values).hasSize(1);
        return JSON.readTree(values.get(0));
    }

    static List<String> collect(Flow.Publisher<String> publisher) throws Exception {
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> publisher.subscribe(new Flow.Subscriber<>() {
            private final List<String> values = new ArrayList<>();
            private Flow.Subscription subscription;
            public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
            public void onNext(String value) { values.add(value); subscription.request(1); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(values); }
        })).exceptionally(error -> { done.completeExceptionally(error); return null; });
        return done.get(20, TimeUnit.SECONDS);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SingleApplication {
        @Bean ControlledHandler handler() { return new ControlledHandler("single"); }
        @Bean CustomerController customerController() { return new CustomerController(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class HostedApplication {
        @Bean HostedAgentDefinitions definitions() {
            return HostedAgentDefinitions.builder().add("a", new ControlledHandler("a"))
                    .add("b", new ControlledHandler("b")).defaultAgent("b").build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {"com.openjiuwen.service.app.controller", "com.openjiuwen.service.app.security"})
    static class WideScan { }

    @RestController
    static class CustomerController {
        @GetMapping("/customer") String customer() { return "customer-ok"; }
    }

    static class ControlledHandler implements AgentHandler {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        volatile ServeRequest last;
        final Map<String, ServeRequest> requests = new ConcurrentHashMap<>();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        ControlledHandler(String name) { this.name = name; }
        @Override public QueryResponse query(ServeRequest request) {
            calls.incrementAndGet();
            last = request;
            requests.put(request.getConversationId(), request);
            if (request.lastUserQuery().equals("block")) {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("test latch timeout"); }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            Object result = request.lastUserQuery().equals("wait")
                    ? Map.of("_interrupt", Map.of("message", "Please answer", "marker", "retained"))
                    : Map.of("content", name + ":" + request.lastUserQuery());
            return new QueryResponse(result, request.getConversationId());
        }
        @Override public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            QueryResponse response = query(request);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, response.getResult()));
            observer.onComplete();
        }
    }
}
