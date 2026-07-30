/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.ResponseEntity;

/**
 * Tests WebFlux query controller behavior.
 *
 * @since 2026-07-30
 */
@ExtendWith(OutputCaptureExtension.class)
class QueryWebFluxControllerTest {
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
