/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import static com.openjiuwen.service.app.invocation.EmbeddedA2aInvocationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

class EmbeddedRemoteA2aTest {
    @Test
    void methodCallerDiscoversAndStreamsRemoteAgentThenResumesTheSameParentTask() throws Exception {
        try (var remote = new SpringApplicationBuilder(RemoteApplication.class).web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.application.name=embedded-remote", "spring.main.banner-mode=off").run()) {
            int port = remote.getEnvironment().getProperty("local.server.port", Integer.class);
            try (var caller = new SpringApplicationBuilder(CallerApplication.class).web(WebApplicationType.NONE)
                    .properties("spring.application.name=embedded-caller", "openjiuwen.service.http.enabled=false",
                            "openjiuwen.service.a2a.remote-agents[0].name=remote",
                            "openjiuwen.service.a2a.remote-agents[0].url=http://localhost:" + port,
                            "openjiuwen.service.a2a.remote-agents[0].is-streaming=true",
                            "spring.main.banner-mode=off").run()) {
                assertThat(caller.getBean(A2ARemoteAgentCardRegistry.class)).isNotNull();
                assertThat(caller.getBean(RemoteAgentCaller.class)).isNotNull();
                assertThat(caller.getBean(ExternalOutboundSecuritySupport.class)).isNotNull();
                var invoker = caller.getBean(A2aRuntimeInvoker.class);
                List<String> first = collect(invoker.invoke(new A2aInvocationRequest(null,
                        rpc("SendStreamingMessage", null, "remote-journey", "start"), null)));
                assertThat(first).anyMatch(frame -> frame.contains("TASK_STATE_INPUT_REQUIRED"));
                String taskId = null;
                ObjectMapper mapper = new ObjectMapper();
                for (String frame : first) {
                    JsonNode event = mapper.readTree(frame).path("result");
                    for (String key : List.of("task", "statusUpdate", "artifactUpdate")) {
                        if (event.has(key)) {
                            JsonNode value = event.get(key);
                            String candidate = value.path(key.equals("task") ? "id" : "taskId").asText();
                            if (!candidate.isEmpty()) { taskId = candidate; }
                        }
                    }
                }
                assertThat(taskId).isNotEmpty();
                String resume = rpc("SendStreamingMessage", taskId, "remote-journey", "answer");
                List<String> completed = collect(invoker.invoke(new A2aInvocationRequest(null, resume, null)));
                assertThat(completed).anyMatch(frame -> frame.contains("TASK_STATE_COMPLETED"));
                JsonNode task = one(invoker.invoke(new A2aInvocationRequest(null,
                        rpc("GetTask", taskId, null, ""), null))).path("result");
                assertThat(task.path("id").asText()).isEqualTo(taskId);
                assertThat(task.toString()).contains("caller resumed:", "remote answered");
                assertThat(remote.getBean(RemoteHandler.class).calls).isEqualTo(2);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RemoteApplication {
        @Bean RemoteHandler remoteHandler() { return new RemoteHandler(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CallerApplication {
        @Bean AgentHandler callerHandler() { return new CallerHandler(); }
    }

    static class CallerHandler implements AgentHandler {
        @Override public QueryResponse query(ServeRequest request) {
            Object results = request.getMetadata().get("runtime.remoteToolResults");
            if (results instanceof Map<?, ?> values) {
                return new QueryResponse(Map.of("content", "caller resumed:" + values.get("call-remote")),
                        request.getConversationId());
            }
            return new QueryResponse(Map.of("_interrupt", Map.of("batchId", "remote-batch", "items",
                    List.of(Map.of("index", 0, "toolCallId", "call-remote", "toolName", "remote-tool",
                            "message", "please ask", "context", Map.of("_interrupt_kind", "a2a_delegate",
                                    "agentName", "remote"))))), request.getConversationId());
        }
        @Override public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            QueryResponse response = query(request);
            Map<?, ?> result = (Map<?, ?>) response.getResult();
            observer.onNext(result.containsKey("_interrupt")
                    ? new QueryChunk(QueryChunk.TYPE_INTERRUPT, result.get("_interrupt"))
                    : new QueryChunk(QueryChunk.TYPE_CHUNK, result));
            observer.onComplete();
        }
    }

    static class RemoteHandler implements AgentHandler {
        volatile int calls;
        @Override public QueryResponse query(ServeRequest request) {
            calls++;
            return new QueryResponse(request.lastUserQuery().equals("please ask")
                    ? Map.of("_interrupt", Map.of("message", "Remote needs an answer"))
                    : Map.of("content", "remote answered:" + request.lastUserQuery()), request.getConversationId());
        }
        @Override public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            Map<?, ?> result = (Map<?, ?>) query(request).getResult();
            observer.onNext(result.containsKey("_interrupt")
                    ? new QueryChunk(QueryChunk.TYPE_INTERRUPT, result.get("_interrupt"))
                    : new QueryChunk(QueryChunk.TYPE_CHUNK, result));
            observer.onComplete();
        }
    }
}
