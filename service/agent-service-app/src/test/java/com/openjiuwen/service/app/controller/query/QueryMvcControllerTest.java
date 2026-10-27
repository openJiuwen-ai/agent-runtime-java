/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests MVC query controller behavior.
 *
 * @since 2026-07-30
 */
@ExtendWith(OutputCaptureExtension.class)
class QueryMvcControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorChunkIsWrittenAsActualSseDataBeforeNormalCompletion() throws Exception {
        var orchestrator = org.mockito.Mockito.mock(ServeOrchestrator.class);
        var failure = new IllegalStateException("Agent execution failed");
        org.mockito.Mockito.doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("content", "partial")));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR,
                    Map.of("type", "error", "error", failure.getMessage())));
            observer.onError(failure);
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        }).when(orchestrator).streamQuery(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("serveOrchestrator", orchestrator);
        var mvc = MockMvcBuilders.standaloneSetup(new QueryMvcController(beans.getBeanProvider(ServeOrchestrator.class),
                beans.getBeanProvider(AgentReadiness.class), objectMapper)).build();
        var result = mvc.perform(post("/v1/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversation_id\":\"sse-model\",\"message\":\"hello\","
                        + "\"stream\":true,\"model_name\":\"b\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getAsyncResult(5000)).isNull();
        String wire = result.getResponse().getContentAsString();
        assertThat(wire).contains("data:", "\"type\":\"error\"", "Agent execution failed", "partial")
                .doesNotContain("result_type", "secret");
    }

    @Test
    void malformedModelSelectionReturns400BeforeExecutionOrSse() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var orchestrator = org.mockito.Mockito.mock(ServeOrchestrator.class);
        beans.registerSingleton("serveOrchestrator", orchestrator);
        var controller = new QueryMvcController(beans.getBeanProvider(ServeOrchestrator.class),
                beans.getBeanProvider(AgentReadiness.class), objectMapper);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String value : java.util.List.of("123", "true", "[]", "{}", "\"   \"")) {
            for (boolean isStreaming : new boolean[] {false, true}) {
                mvc.perform(post("/v1/query").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"conversation_id\":\"invalid-model\",\"message\":\"hello\",\"stream\":" + isStreaming
                                + ",\"model_name\":" + value + "}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(request().asyncNotStarted());
            }
        }
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void streamingQueryOnErrorWritesSseErrorAndLogsConversationId(CapturedOutput output) throws Exception {
        IllegalStateException failure = new IllegalStateException("stream failed");
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("serveOrchestrator", failingOrchestrator(failure));
        QueryMvcController controller = new QueryMvcController(beanFactory.getBeanProvider(ServeOrchestrator.class),
                beanFactory.getBeanProvider(AgentReadiness.class), new ObjectMapper());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc
                .perform(post("/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversation_id":"conversation-mvc-error","message":"fail","stream":true}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        Object asyncResult = result.getAsyncResult(5000L);

        assertThat(asyncResult).isNull();
        assertThat(result.getResponse().getContentAsString())
                .contains("\"type\":\"error\"", "\"code\":\"AGENT_EXECUTION_FAILED\"",
                        "\"error\":\"agent execution failed\"",
                        "\"conversation_id\":\"conversation-mvc-error\"")
                .doesNotContain("stream failed");
        assertThat(output).contains("Stream query failed for conversation_id=conversation-mvc-error")
                .contains("java.lang.IllegalStateException: stream failed");
    }

    @Test
    void terminalErrorSendFailureCompletesEmitter() throws Exception {
        IllegalStateException failure = new IllegalStateException("stream failed");
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("serveOrchestrator", failingOrchestrator(failure));
        QueryMvcController controller = new QueryMvcController(beanFactory.getBeanProvider(ServeOrchestrator.class),
                beanFactory.getBeanProvider(AgentReadiness.class), objectMapper);
        SseEmitter emitter = org.mockito.Mockito.mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new IOException("closed")).when(emitter)
                .send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));

        ReflectionTestUtils.invokeMethod(controller, "streamToEmitter",
                beanFactory.getBean(ServeOrchestrator.class), org.mockito.Mockito.mock(ServeRequest.class), emitter,
                new AtomicBoolean(false));

        org.mockito.Mockito.verify(emitter).complete();
    }

    @Test
    void restSseMapsRemoteArtifactMetadataWithoutWrappingLocalOutput() throws Exception {
        Artifact artifact = Artifact.builder().artifactId("remote-artifact").parts(new TextPart("remote text"))
                .metadata(Map.of("agentEvent", Map.of("type", "output",
                        "source", Map.of("agentId", "B", "taskId", "task-b"))))
                .build();
        QueryChunk remote = new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                new TaskArtifactUpdateEvent("task-b", artifact, "context-b", false, true, Map.of()));

        var remotePayload = objectMapper.readTree(QuerySseSupport.toJson(remote, objectMapper));
        assertThat(remotePayload.path("content").asText()).isEqualTo("remote text");
        assertThat(remotePayload.path("metadata").path("agentEvent").path("source").path("agentId").asText())
                .isEqualTo("B");

        QueryChunk local = new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("content", "local"));
        assertThat(QuerySseSupport.toJson(local, objectMapper)).isEqualTo("{\"content\":\"local\"}");
    }

    @Test
    void restSseAcceptsRemoteArtifactWithoutMetadata() throws Exception {
        Artifact artifact = Artifact.builder().artifactId("remote-artifact").parts(new TextPart("remote text")).build();
        QueryChunk remote = new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                new TaskArtifactUpdateEvent("task-b", artifact, "context-b", false, true, Map.of()));

        var payload = objectMapper.readTree(QuerySseSupport.toJson(remote, objectMapper));

        assertThat(payload.path("content").asText()).isEqualTo("remote text");
        assertThat(payload.path("metadata").has("agentEvent")).isTrue();
        assertThat(payload.path("metadata").path("agentEvent").isNull()).isTrue();
    }

    private static ServeOrchestrator failingOrchestrator(RuntimeException failure) {
        return new ServeOrchestrator() {
            @Override
            public QueryResponse query(ServeRequest request) {
                throw new UnsupportedOperationException("Synchronous query is not used by this test");
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onError(failure);
            }

            @Override
            public void cancelActive(String conversationId) {
                throw new UnsupportedOperationException("Cancellation is not used by this test");
            }

            @Override
            public void resetConversation(String conversationId) {
                throw new UnsupportedOperationException("Reset is not used by this test");
            }
        };
    }
}
