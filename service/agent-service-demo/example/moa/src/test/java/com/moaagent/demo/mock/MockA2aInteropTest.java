/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moaagent.demo.MoaAgentDemoApplication;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the mock card and streaming endpoint with the runtime A2A client.
 *
 * @since 0.1.3
 */
class MockA2aInteropTest {
    @Test
    void mockStartsWithoutModelAndActualRuntimeSdkConsumesCardAndStream() throws Exception {
        try (var context = new SpringApplicationBuilder(MoaAgentDemoApplication.class).run(
                "--spring.profiles.active=mock", "--server.port=0", "--logging.level.root=ERROR")) {
            assertThat(context.containsBean("moaAgentHandler")).isFalse();
            assertThat(context.getBeansOfType(AgentHandler.class)).isEmpty();
            if (!(context instanceof WebServerApplicationContext webContext)) {
                throw new AssertionError("Expected an embedded web server");
            }
            int port = webContext.getWebServer().getPort();
            String base = "http://127.0.0.1:" + port + "/a2a/mock";
            var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/agent-card.json")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var card = JsonUtil.fromJson(response.body(), AgentCard.class);
            assertThat(card.url()).isEqualTo(base);
            var registry = new A2ARemoteAgentCardRegistry();
            registry.register("mock-tourism", card, 10, true);
            var caller = new A2ARemoteAgentClient(registry);
            try {
                List<TaskState> states = new ArrayList<>();
                var observer = new RemoteAgentCaller.EventObserver() {
                    @Override
                    public void onArtifact(TaskArtifactUpdateEvent event) {
                        // The caller collects artifact content; this observer records only lifecycle states.
                    }

                    @Override
                    public void onStatus(TaskStatusUpdateEvent event) {
                        states.add(event.status().state());
                    }
                };
                String query = new ObjectMapper().writeValueAsString(Map.of(
                        "intent", "attractions.search", "query", "查询杭州有哪些演示景点"));
                var outcome = caller.callOutcome(new RemoteCall("mock-tourism", query,
                        "context", null, Map.of(), Map.of(), true), observer).get(15, TimeUnit.SECONDS);
                assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
                assertThat(states).containsSubsequence(TaskState.TASK_STATE_SUBMITTED,
                        TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED);
                assertThat(new ObjectMapper().readTree(outcome.result()).path("city").asText()).isEqualTo("杭州");
                var envelope = new ObjectMapper().writeValueAsString(Map.of(
                        "intent", "attractions.search", "query", "city=北京"));
                var skill = caller.callOutcome(new RemoteCall("mock-tourism", envelope, "skill-context", null,
                        Map.of(), Map.of(), true), observer).get(15, TimeUnit.SECONDS);
                assertThat(new ObjectMapper().readTree(skill.result()).path("city").asText()).isEqualTo("北京");
            } finally {
                caller.shutdown();
            }
        }
    }
}
