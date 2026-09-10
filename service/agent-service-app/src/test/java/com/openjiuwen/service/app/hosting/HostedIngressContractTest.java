/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@SpringBootTest(classes = HostedIngressContractTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=hosted-ingress-test", "openjiuwen.service.query.webflux.enabled=true"})
@AutoConfigureTestRestTemplate
class HostedIngressContractTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private HostedRuntimeCatalog catalog;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void standardRestRoutesBodyOnlyAndPreservesResponseShapeAcrossAllQueryEntries() throws Exception {
        for (String path : List.of("/v1/query", "/query", "/v1/query/reactive")) {
            var body = restBody("a", false);
            var selected = post(path + "?agentId=b", body);
            assertThat(selected.getStatusCode().value()).isEqualTo(200);
            JsonNode json = mapper.readTree(selected.getBody());
            assertThat(json.get("result").get("content").asText()).isEqualTo("a:hello");
            assertThat(json.get("conversation_id").asText()).isEqualTo(body.get("conversation_id"));
            assertThat(json.has("agent_id")).isFalse();
            body.remove("agent_id");
            assertThat(post(path + "?agent_id=a", body).getBody()).contains("b:hello");
            body.put("agent_id", null);
            assertThat(post(path, body).getBody()).contains("b:hello");
            body.put("agent_id", "a");
            body.put("stream", true);
            var stream = post(path, body);
            assertThat(stream.getStatusCode().value()).isEqualTo(200);
            assertThat(stream.getHeaders().getContentType().toString()).startsWith("text/event-stream");
            assertThat(stream.getBody()).contains("a:hello").doesNotContain("b:hello");
        }
    }

    @Test
    void resetCallsOnlySelectedHandlerAndLegacyUsesSameRule() {
        for (String path : List.of("/v1/reset_conversation", "/reset_conversation")) {
            String conversation = UUID.randomUUID().toString();
            assertThat(post(path, Map.of("agent_id", "a", "conversation_id", conversation))
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(((EchoHandler) catalog.resolve("a").handler()).cleared).contains(conversation);
            assertThat(((EchoHandler) catalog.resolve("b").handler()).cleared).doesNotContain(conversation);
        }
    }

    @Test
    void rejectsRoutingErrorsAndNonStringDtoBeforeExecution() {
        for (String path : List.of("/v1/query", "/query", "/v1/query/reactive", "/v1/reset_conversation")) {
            var body = restBody("missing", false);
            assertThat(post(path, body).getStatusCode().value()).isEqualTo(404);
            for (Object invalid : List.of("", " ", "bad/id", 123, true, Map.of(), List.of())) {
                body.put("agent_id", invalid);
                assertThat(post(path, body).getStatusCode().value()).as(path + " " + invalid).isEqualTo(400);
            }
        }
    }

    @Test
    void cardDiscoveryAndListMatchDefaultAndNamedExecution() throws Exception {
        JsonNode listing = mapper.readTree(rest.getForObject("/a2a/agents", String.class));
        assertThat(listing.get("agents").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(listing.get("defaultAgent").asText()).isEqualTo("b");
        for (String path : List.of("/.well-known/agent-card.json", "/.well-known/agent.json",
                "/a2a/agents/a/.well-known/agent-card.json", "/a2a/agents/b/.well-known/agent-card.json")) {
            JsonNode card = mapper.readTree(rest.getForObject(path, String.class));
            String expected = path.contains("/agents/a/") ? "a" : "b";
            assertThat(card.get("name").asText()).isEqualTo(expected);
            String url = card.get("supportedInterfaces").get(0).get("url").asText();
            assertThat(post(url, rpc("SendMessage", null, UUID.randomUUID().toString())).getBody())
                    .contains(expected + ":hello");
        }
    }

    @Test
    void a2aFourMethodsUseTheSelectedStoreAndSdkErrorFormat() throws Exception {
        for (String id : List.of("a", "b")) {
            String context = UUID.randomUUID().toString();
            var response = post("/a2a/agents/" + id, rpc("SendMessage", null, context));
            JsonNode envelope = mapper.readTree(response.getBody());
            assertThat(envelope.has("error")).as(response.getBody()).isFalse();
            String taskId = envelope.path("result").path("task").path("id").asText();
            assertThat(taskId).isNotEmpty();
            assertThat(post("/a2a/agents/" + id, rpc("GetTask", taskId, null)).getBody()).contains(taskId);
            assertThat(post("/a2a/agents/" + id, rpc("SubscribeToTask", taskId, null)).getBody()).contains(taskId);
            String other = id.equals("a") ? "b" : "a";
            assertThat(mapper.readTree(post("/a2a/agents/" + other, rpc("GetTask", taskId, null)).getBody())
                    .has("error")).isTrue();
            assertThat(post("/a2a/agents/" + id, rpc("SendStreamingMessage", null, UUID.randomUUID().toString()))
                    .getBody()).contains(id + ":hello");
        }
        JsonNode error = mapper.readTree(post("/a2a/agents/unknown", rpc("SendMessage", null, "error-c")).getBody());
        assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(error.path("error").has("details")).isFalse();
        assertThat(error.path("error").has("data")).isFalse();
        assertThat(error.has("result")).isFalse();
    }

    @Test
    void explicitTaskCannotCrossTargetOrChangeContext() throws Exception {
        String taskId = UUID.randomUUID().toString();
        Task task = Task.builder().id(taskId).contextId("original")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED)).build();
        catalog.resolve("a").taskStore().save(task, true);
        var mismatch = post("/a2a/agents/a", rpc("SendMessage", taskId, "different"));
        assertThat(mapper.readTree(mismatch.getBody()).path("error").path("code").asInt()).isEqualTo(-32602);
        var missing = post("/a2a/agents/b", rpc("SendMessage", taskId, "original"));
        assertThat(mapper.readTree(missing.getBody()).has("error")).isTrue();
        assertThat(catalog.resolve("b").taskStore().get(taskId)).isNull();
        assertThat(catalog.resolve("a").taskStore().get(taskId)).isSameAs(task);
    }

    private ResponseEntity<String> post(String path, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            headers.setContentLength(bytes.length);
            return rest.postForEntity(path, new HttpEntity<>(bytes, headers), String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize test request", failure);
        }
    }

    private static Map<String, Object> restBody(String agentId, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_id", agentId);
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("message", "hello");
        body.put("stream", stream);
        return body;
    }

    private static Map<String, Object> rpc(String method, String taskId, String context) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (method.equals("GetTask") || method.equals("SubscribeToTask")) {
            params.put("id", taskId);
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "ROLE_USER");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("contextId", context);
            message.put("parts", List.of(Map.of("text", "hello")));
            if (taskId != null) {
                message.put("taskId", taskId);
            }
            params.put("message", message);
        }
        return Map.of("jsonrpc", "2.0", "id", "req", "method", method, "params", params);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean(destroyMethod = "")
        AgentHandler firstHandler() {
            return new EchoHandler("a");
        }

        @Bean(destroyMethod = "")
        AgentHandler secondHandler() {
            return new EchoHandler("b");
        }

        @Bean
        HostedAgentDefinitions definitions(@Qualifier("firstHandler") AgentHandler first,
                @Qualifier("secondHandler") AgentHandler second) {
            return HostedAgentDefinitions.builder().add("a", first).add("b", second).defaultAgent("b").build();
        }
    }

    static final class EchoHandler implements AgentHandler {
        private final String name;
        private final List<String> cleared = new CopyOnWriteArrayList<>();

        EchoHandler(String name) {
            this.name = name;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(Map.of("role", "assistant", "content", name + ":" + request.lastUserQuery()),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
            observer.onComplete();
        }

        @Override
        public void clearSession(String conversationId) {
            cleared.add(conversationId);
        }
    }
}
