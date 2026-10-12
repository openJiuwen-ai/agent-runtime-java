/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.ResponseEntity;

/**
 * Tests WebFlux query controller behavior.
 *
 * @since 2026-07-30
 */
@ExtendWith(OutputCaptureExtension.class)
class QueryWebFluxControllerTest {
    @Test
    void errorDataSurvivesSseEncodingAndPartialOutputDoesNotMaskFailure() {
        var orchestrator = org.mockito.Mockito.mock(ServeOrchestrator.class);
        var failure = new IllegalStateException("Agent execution failed");
        org.mockito.Mockito.doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk("chunk", java.util.Map.of("content", "partial")));
            observer.onNext(new QueryChunk("error",
                    java.util.Map.of("type", "error", "error", failure.getMessage())));
            observer.onError(failure);
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        }).when(orchestrator).streamQuery(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("serveOrchestrator", orchestrator);
        var controller = new QueryWebFluxController(beans.getBeanProvider(ServeOrchestrator.class),
                beans.getBeanProvider(AgentReadiness.class), new ObjectMapper());
        var request = new QueryRequest();
        request.setConversationId("sse-model");
        request.setMessage("hello");
        request.setModelName("b");
        request.setStream(true);
        var response = controller.queryReactive(request, new HttpHeaders()).block();
        if (!(response.getBody() instanceof Flux<?> events)) {
            throw new AssertionError("Expected a streaming response");
        }
        StepVerifier.create(events)
                .assertNext(event -> assertThat(sseData(event)).contains("partial"))
                .assertNext(event -> assertThat(sseData(event)).contains("\"type\":\"error\"", "Agent execution failed")
                        .doesNotContain("secret"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();
    }

    private static String sseData(Object event) {
        if (!(event instanceof ServerSentEvent<?> sse)) {
            throw new AssertionError("Expected a server-sent event");
        }
        assertThat(sse.data()).isNotNull();
        return sse.data().toString();
    }

    @Test
    void actualJsonDecoderRejectsMalformedSelectionBeforeExecutionOrSse() {
        var beans = new DefaultListableBeanFactory();
        var orchestrator = org.mockito.Mockito.mock(ServeOrchestrator.class);
        beans.registerSingleton("serveOrchestrator", orchestrator);
        var controller = new QueryWebFluxController(beans.getBeanProvider(ServeOrchestrator.class),
                beans.getBeanProvider(AgentReadiness.class), new ObjectMapper());
        var client = org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller).build();
        for (String value : java.util.List.of("123", "true", "[]", "{}", "\"   \"")) {
            for (boolean isStreaming : new boolean[] {false, true}) {
                client.post().uri("/v1/query/reactive").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue("{\"conversation_id\":\"invalid-model\",\"message\":\"hello\","
                                + "\"stream\":" + isStreaming
                                + ",\"model_name\":" + value + "}")
                        .exchange().expectStatus().isBadRequest();
            }
        }
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void streamingQueryOnErrorPropagatesFailureAndLogsConversationId(CapturedOutput output) {
        IllegalStateException failure = new IllegalStateException("stream failed");
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("serveOrchestrator", failingOrchestrator(failure));
        QueryWebFluxController controller = new QueryWebFluxController(
                beanFactory.getBeanProvider(ServeOrchestrator.class),
                beanFactory.getBeanProvider(AgentReadiness.class), new ObjectMapper());
        QueryRequest request = new QueryRequest();
        request.setConversationId("conversation-webflux-error");
        request.setMessage("fail");
        request.setStream(true);

        ResponseEntity<?> response = controller.queryReactive(request, new HttpHeaders()).block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isInstanceOf(Flux.class);
        StepVerifier.create((Flux<?>) response.getBody())
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
        assertThat(output).contains("Stream query failed for conversation_id=conversation-webflux-error")
                .contains("java.lang.IllegalStateException: stream failed");
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
