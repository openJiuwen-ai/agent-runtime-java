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

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for the read-only admission pre-check in
 * {@link A2aJsonRpcController}. The controller never acquires or releases a
 * permit — authoritative admission happens in
 * {@code A2AAgentExecutor.executeRequest()}.
 *
 * @since 0.1.2
 */
class A2aJsonRpcControllerAdmissionTest {

    @Test
    void sendMessage_rejectedWith503_whenLimitReached() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = gateAtLimit();
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).asString().contains("concurrent task limit reached");
        verify(handler, never()).onMessageSend(any(), any());
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void sendStreamingMessage_rejectedWith503_whenLimitReached() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = gateAtLimit();
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendStreamingMessageJson(), servletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(handler, never()).onMessageSendStream(any(), any());
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void sendMessage_admitted_whenUnderLimit() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any())).thenReturn(completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // The controller pre-check is read-only: authoritative admission
        // (tryAcquire/release) belongs to A2AAgentExecutor. A tryAcquire here
        // would double-count permits.
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void sendMessage_unlimitedLimit_skipsPreCheck() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any())).thenReturn(completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(-1);
        when(gate.currentCount()).thenReturn(Integer.MAX_VALUE);
        A2aJsonRpcController controller = newController(handler, gate);

        ResponseEntity<?> response = controller.handleJsonRpc(sendMessageJson(), servletRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void sendMessage_handlerException_noGateInteraction() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(MessageSendParams.class), any()))
                .thenThrow(new RuntimeException("handler failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        A2aJsonRpcController controller = newController(handler, gate);

        controller.handleJsonRpc(sendMessageJson(), servletRequest());

        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void sendStreamingMessage_handlerException_noGateInteraction() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSendStream(any(MessageSendParams.class), any()))
                .thenThrow(new RuntimeException("stream handler failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        A2aJsonRpcController controller = newController(handler, gate);

        controller.handleJsonRpc(sendStreamingMessageJson(), servletRequest());

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

    private static TaskAdmissionGate gateAtLimit() {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(1);
        when(gate.currentCount()).thenReturn(1);
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
        return new MockHttpServletRequest("POST", "/a2a");
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
                + "\",\"params\":{\"id\":\"task-1\"}}";
    }

    private static Task completedTask() {
        return Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }
}
