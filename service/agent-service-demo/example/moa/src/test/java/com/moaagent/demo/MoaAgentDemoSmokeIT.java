/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moaagent.demo.mock.MockBusinessService;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Zero-LLM smoke: the ported demo boots the mock role on a real HTTP port and the
 * full five-operation tourism chain completes through the runtime A2A client,
 * in the spirit of the deepagent example IT. No model, Redis or other infra needed.
 *
 * @since 0.1.4
 */
class MoaAgentDemoSmokeIT {
    private static final TypeReference<Map<String, Object>> RESULT_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @Timeout(120)
    void mockRoleBootsAndFullTourismChainCompletesOverA2a() throws Exception {
        try (var context = new SpringApplicationBuilder(MoaAgentDemoApplication.class).run(
                "--spring.profiles.active=mock", "--server.port=0", "--logging.level.root=ERROR")) {
            assertThat(context.getBeansOfType(AgentHandler.class)).isEmpty();
            assertThat(context.getBean(MockBusinessService.class)).isNotNull();
            if (!(context instanceof WebServerApplicationContext webContext)) {
                throw new AssertionError("Expected an embedded web server");
            }
            int port = webContext.getWebServer().getPort();
            String base = "http://127.0.0.1:" + port + "/a2a/mock";
            var http = HttpClient.newHttpClient();
            var cardResponse = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/.well-known/agent-card.json")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cardResponse.statusCode()).isEqualTo(200);
            AgentCard card = JsonUtil.fromJson(cardResponse.body(), AgentCard.class);
            assertThat(card.url()).isEqualTo(base);

            var registry = new A2ARemoteAgentCardRegistry();
            registry.register("mock-tourism", card, 10, true);
            var caller = new A2ARemoteAgentClient(registry);
            try {
                List<TaskState> states = new ArrayList<>();
                var observer = new RemoteAgentCaller.EventObserver() {
                    @Override
                    public void onArtifact(TaskArtifactUpdateEvent event) {
                        // Business results are asserted from callOutcome; lifecycle goes to onStatus.
                    }

                    @Override
                    public void onStatus(TaskStatusUpdateEvent event) {
                        states.add(event.status().state());
                    }
                };

                var attractions = call(caller, observer, "attractions.search", "查询杭州有哪些演示景点");
                assertThat(attractions.get("city")).isEqualTo("杭州");
                assertThat(attractions.get("simulated")).isEqualTo(true);
                assertThat(String.valueOf(attractions.get("selection_id"))).isNotBlank();
                assertLifecycle(states);

                LocalDate start = LocalDate.now().plusDays(30);
                var plan = call(caller, observer, "trip.plan", "selection_id=" + attractions.get("selection_id")
                        + "\nstart_date=" + start + "\ndays=3\npeople=2");
                assertThat(plan.get("city")).isEqualTo("杭州");
                assertThat(plan.get("people")).isEqualTo(2);
                assertThat((List<?>) plan.get("itinerary")).hasSize(3);
                assertLifecycle(states);

                var search = call(caller, observer, "hotel.search", "city=杭州\ncheck_in=" + start
                        + "\ncheck_out=" + start.plusDays(3) + "\npeople=2\nrooms=1");
                assertThat(String.valueOf(search.get("room_selection_id"))).isNotBlank();
                assertLifecycle(states);

                var quote = call(caller, observer, "hotel.quote",
                        "room_selection_id=" + search.get("room_selection_id"));
                // Standard room 26800 cents/night, 3 nights, 1 room.
                assertThat(((Number) quote.get("total_cents")).longValue()).isEqualTo(26800L * 3);
                assertThat(String.valueOf(quote.get("confirmation_text"))).contains("模拟预订");
                assertLifecycle(states);

                String booking = "quote_id=" + quote.get("quote_id")
                        + "\nconfirmation_text=" + quote.get("confirmation_text");
                var receipt = call(caller, observer, "hotel.book", booking);
                assertThat(String.valueOf(receipt.get("booking_id"))).startsWith("demo-");
                assertThat(receipt.get("simulated")).isEqualTo(true);
                assertLifecycle(states);
                assertThat(call(caller, observer, "hotel.book", booking)).isEqualTo(receipt);
            } finally {
                caller.shutdown();
            }
        }
    }

    private Map<String, Object> call(A2ARemoteAgentClient caller, RemoteAgentCaller.EventObserver observer,
            String intent, String query) throws Exception {
        String envelope = mapper.writeValueAsString(Map.of("intent", intent, "query", query));
        var outcome = caller
                .callOutcome(new RemoteCall("mock-tourism", envelope, "smoke-context", null, Map.of(), Map.of(), true),
                        observer)
                .get(15, TimeUnit.SECONDS);
        assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        return mapper.readValue(outcome.result(), RESULT_TYPE);
    }

    private static void assertLifecycle(List<TaskState> states) {
        assertThat(states).containsSubsequence(TaskState.TASK_STATE_SUBMITTED,
                TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED);
        states.clear();
    }
}
