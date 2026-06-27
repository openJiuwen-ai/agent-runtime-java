/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;

@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class A2aIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/a2a/", new HttpEntity<>(body, h), String.class);
    }

    private static Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Map<String, Object> msgParams(String text, String contextId) {
        return Map.of("message",
                Map.of("role", "ROLE_USER", "parts", List.of(Map.of("text", text)), "contextId", contextId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String body) throws Exception {
        return mapper.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskFrom(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        // SendMessage has {"task":{...}} wrapper; GetTask has Task fields directly
        if (result.containsKey("task")) {
            return (Map<String, Object>) result.get("task");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String firstArtifactText(Map<String, Object> task) {
        var artifacts = (List<Map<String, Object>>) task.get("artifacts");
        if (artifacts == null || artifacts.isEmpty())
            return null;
        var parts = (List<Map<String, Object>>) artifacts.get(0).get("parts");
        if (parts == null || parts.isEmpty())
            return null;
        return (String) parts.get(0).get("text");
    }

    // ======================== AgentCard ========================

    @Test
    void agentCardAccessibleOnAllPaths() throws Exception {
        var std = json(rest.getForObject("/.well-known/agent-card.json", String.class));
        assertThat(std).containsKeys("name", "supportedInterfaces", "skills");

        var compat = json(rest.getForObject("/.well-known/agent.json", String.class));
        assertThat(compat).isEqualTo(std);

        var prefixed = json(rest.getForObject("/a2a/.well-known/agent-card.json", String.class));
        assertThat(prefixed).isEqualTo(std);
    }

    // ======================== SendMessage ========================

    @Test
    @SuppressWarnings("unchecked")
    void sendMessageReturnsCompletedTaskWithAgentOutput() throws Exception {
        var resp = postA2a(rpc("SendMessage", 1, msgParams("hello", "c1")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = json(resp.getBody());
        assertThat(body.get("jsonrpc")).isEqualTo("2.0");
        assertThat(body.get("id")).isEqualTo(1.0);

        Map<String, Object> task = taskFrom(body);
        Map<String, Object> status = (Map<String, Object>) task.get("status");
        assertThat(status.get("state")).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(firstArtifactText(task)).contains("turn1:hello");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiTurnRetainsContextAcrossSendMessage() throws Exception {
        var t1 = json(postA2a(rpc("SendMessage", 2, msgParams("a", "a2a-multi"))).getBody());
        var t2 = json(postA2a(rpc("SendMessage", 3, msgParams("b", "a2a-multi"))).getBody());

        assertThat(firstArtifactText(taskFrom(t1))).contains("turn1:a");
        assertThat(firstArtifactText(taskFrom(t2))).contains("turn2:b|prev=a");
    }

    @Test
    void unknownMethodReturnsJsonRpcError() throws Exception {
        var resp = postA2a(Map.of("jsonrpc", "2.0", "id", 99, "method", "Bogus", "params", Map.of()));

        Map<String, Object> body = json(resp.getBody());
        Map<String, Object> err = (Map<String, Object>) body.get("error");
        assertThat(err.get("code")).isEqualTo(-32601);
    }

    // ======================== SendStreamingMessage SSE ========================

    @Test
    void sendStreamingMessageReturnsSseWithTaskEvents() {
        var resp = postA2a(rpc("SendStreamingMessage", 4, msgParams("ss", "c-sse")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(resp.getBody()).contains("event:jsonrpc");
    }

    // ======================== GetTask ========================

    @Test
    @SuppressWarnings("unchecked")
    void getTaskRetrievesCreatedTask() throws Exception {
        var createResp = postA2a(rpc("SendMessage", 5, msgParams("gt", "c-get")));
        var created = json(createResp.getBody());
        String taskId = (String) taskFrom(created).get("id");

        var getResp = postA2a(rpc("GetTask", 6, Map.of("id", taskId)));
        var got = json(getResp.getBody());
        Map<String, Object> gotTask = (Map<String, Object>) got.get("result");

        assertThat(gotTask.get("id")).isEqualTo(taskId);
        assertThat(gotTask.get("contextId")).isEqualTo("c-get");
    }

    // ======================== ResetConversation ========================

    @Test
    void resetConversationClearsSession() throws Exception {
        postA2a(rpc("SendMessage", 7, msgParams("before-reset", "c-reset")));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var resetResp = rest.postForEntity("/v1/reset_conversation",
                new HttpEntity<>(Map.of("conversation_id", "c-reset"), h), String.class);
        assertThat(resetResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var afterResp = postA2a(rpc("SendMessage", 8, msgParams("after-reset", "c-reset")));
        var after = json(afterResp.getBody());
        assertThat(firstArtifactText(taskFrom(after))).isEqualTo("turn1:after-reset");
    }

    // ======================== Query REST metadata ========================

    @Test
    @SuppressWarnings("unchecked")
    void queryRestMetadataContainsHeadersQueryPathBody() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-User-ID", "test-user");

        var body = Map.of("conversation_id", "c-meta", "stream", false, "message", "hello-meta");
        var resp = rest.postForEntity("/v1/query?type=controller&workspace_id=10", new HttpEntity<>(body, h),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        Map<String, Object> meta = (Map<String, Object>) result.get("_metadata");

        assertThat(meta).isNotNull();
        // headers captured
        assertThat(meta).containsKey("headers");
        Map<String, Object> hdrs = (Map<String, Object>) meta.get("headers");
        assertThat(hdrs).containsEntry("x-user-id", "test-user");
        // query params captured
        assertThat(meta).containsKey("query");
        Map<String, Object> q = (Map<String, Object>) meta.get("query");
        assertThat(q).containsEntry("type", "controller");
        assertThat(q).containsEntry("workspace_id", "10");
        // path captured
        assertThat(meta).containsKey("path");
        assertThat((String) meta.get("path")).isEqualTo("/v1/query");
        // body preserved as-is (not reconstructed)
        assertThat(meta).containsKey("body");
        Map<String, Object> b = (Map<String, Object>) meta.get("body");
        assertThat(b).containsEntry("conversation_id", "c-meta");
        assertThat(b).containsEntry("message", "hello-meta");
    }
}
