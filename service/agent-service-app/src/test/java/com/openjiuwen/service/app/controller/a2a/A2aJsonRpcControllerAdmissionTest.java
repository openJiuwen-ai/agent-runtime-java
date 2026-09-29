/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.Flow;

/**
 * Unit tests for the admission acquisition in {@link A2aJsonRpcController}.
 * The controller acquires a permit synchronously (HTTP 503 on rejection) and
 * hands it over to {@code A2AAgentExecutor} via the call-context marker; on a
 * synchronous failure before the executor adopts the permit, the controller
 * releases it itself.
 *
 * @since 0.1.2
 */
class A2aJsonRpcControllerAdmissionTest {
    @Test
    void sendMessage_rejectedWith503_whenAcquireFails() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = rejectingGate();
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).asString().contains("concurrent task limit reached");
        verify(handler, never()).onMessageSend(any(), any());
        // Nothing was acquired, so nothing may be released.
        verify(gate, never()).release();
    }

    @Test
    void sendStreamingMessage_rejectedWith503_whenAcquireFails() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = rejectingGate();
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendStreamingMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(handler, never()).onMessageSendStream(any(), any());
        verify(gate, never()).release();
    }

    @Test
    void sendMessage_admitted_marksPermitForExecutorHandover() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any())).thenReturn(completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        when(gate.tryAcquire()).thenReturn(true);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<ServerCallContext> ctxCaptor = ArgumentCaptor.forClass(ServerCallContext.class);
        verify(handler).onMessageSend(any(MessageSendParams.class), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getState())
                .containsKey(A2AAgentExecutor.PRE_ACQUIRED_ADMISSION_KEY);
        // The executor adopts (and later releases) the permit — the controller
        // must not release it on the success path.
        verify(gate, never()).release();
    }

    @Test
    void sendStreamingMessage_admitted_marksPermitForExecutorHandover() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSendStream(any(MessageSendParams.class), any())).thenReturn(completedStreamPublisher());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        when(gate.tryAcquire()).thenReturn(true);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendStreamingMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<ServerCallContext> ctxCaptor = ArgumentCaptor.forClass(ServerCallContext.class);
        verify(handler).onMessageSendStream(any(MessageSendParams.class), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getState())
                .containsKey(A2AAgentExecutor.PRE_ACQUIRED_ADMISSION_KEY);
        verify(gate, never()).release();
    }

    @Test
    void sendMessage_syncHandlerFailure_releasesPermit() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any()))
                .thenThrow(new RuntimeException("handler failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        when(gate.tryAcquire()).thenReturn(true);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The executor never ran, so the controller must return the permit.
        verify(gate).release();
    }

    @Test
    void sendMessage_syncA2AError_releasesPermit() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any()))
                .thenThrow(new A2AError(A2AErrorCodes.INTERNAL.code(), "boom", null));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        when(gate.tryAcquire()).thenReturn(true);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(gate).release();
    }

    @Test
    void sendStreamingMessage_syncHandlerFailure_releasesPermit() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSendStream(any(MessageSendParams.class), any()))
                .thenThrow(new RuntimeException("stream handler failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        when(gate.tryAcquire()).thenReturn(true);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendStreamingMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(gate).release();
    }

    @Test
    void sendMessage_unlimitedLimit_skipsAcquisition() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any())).thenReturn(completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(-1);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void getTask_notThrottled() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onGetTask(any(TaskQueryParams.class), any())).thenReturn(completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        A2aJsonRpcController controller = newController(handler, gate);

        controller.handleJsonRpc(getTaskJson(), servletRequest());

        verifyNoInteractions(gate);
    }

    @Test
    void admissionGateNull_skipsCheck() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any())).thenReturn(completedTask());
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        A2aJsonRpcController controller = new A2aJsonRpcController(handler);
        controller.setAdmissionGateProvider(provider);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private static TaskAdmissionGate rejectingGate() {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(1);
        when(gate.currentCount()).thenReturn(1);
        when(gate.tryAcquire()).thenReturn(false);
        return gate;
    }

    private static A2aJsonRpcController newController(RequestHandler handler, TaskAdmissionGate gate) {
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        A2aJsonRpcController controller = new A2aJsonRpcController(handler);
        controller.setAdmissionGateProvider(provider);
        return controller;
    }

    private static MockHttpServletRequest servletRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent(new byte[] {'{', '}'});
        return request;
    }

    private static String sendMessageJson() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"" + A2AMethods.SEND_MESSAGE_METHOD
                + "\",\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\","
                + "\"contextId\":\"ctx-1\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}}";
    }

    private static String sendStreamingMessageJson() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"" + A2AMethods.SEND_STREAMING_MESSAGE_METHOD
                + "\",\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\","
                + "\"contextId\":\"ctx-1\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}}";
    }

    private static String getTaskJson() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"" + A2AMethods.GET_TASK_METHOD
                + "\",\"params\":{\"id\":\"task-1\"}}}";
    }

    private static Task completedTask() {
        return Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }

    private static Flow.Publisher<StreamingEventKind> completedStreamPublisher() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // no events to deliver
                }

                @Override
                public void cancel() {
                    // nothing to cancel
                }
            });
            subscriber.onComplete();
        };
    }
}
