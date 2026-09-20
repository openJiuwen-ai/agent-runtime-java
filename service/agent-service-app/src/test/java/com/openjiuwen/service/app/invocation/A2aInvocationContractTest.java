/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import static com.openjiuwen.service.app.invocation.EmbeddedA2aInvocationTest.one;
import static com.openjiuwen.service.app.invocation.EmbeddedA2aInvocationTest.rpc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.A2aJsonRpcController;
import com.openjiuwen.service.app.controller.a2a.A2aJsonRpcDispatcher;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

class A2aInvocationContractTest {
    private final RequestHandler handler = mock(RequestHandler.class);
    private final TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
    private final A2AProperties properties = new A2AProperties();
    private final DefaultAgentReadiness readiness = new DefaultAgentReadiness();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final A2aJsonRpcDispatcher dispatcher;
    private final A2aRuntimeInvoker invoker;

    A2aInvocationContractTest() {
        readiness.markAgentLoaded(true);
        when(gate.limit()).thenReturn(-1);
        beans.registerSingleton("readiness", readiness);
        beans.registerSingleton("gate", gate);
        dispatcher = new A2aJsonRpcDispatcher(() -> handler, beans.getBeanProvider(TaskAdmissionGate.class),
                properties, null);
        invoker = new DefaultA2aRuntimeInvoker(dispatcher, properties, beans.getBeanProvider(AgentReadiness.class),
                beans.getBeanProvider(com.openjiuwen.service.app.hosting.HostedIngressResolver.class));
    }

    @Test
    void parsingSizeAndReadinessErrorsHaveNoExecutionOrAdmissionSideEffects() throws Exception {
        for (var error : Map.of("{", -32700, "[]", -32600,
                "{\"jsonrpc\":\"2.0\",\"id\":\"id\",\"method\":\"message/send\"}", -32601,
                "{\"jsonrpc\":\"2.0\",\"id\":\"id\",\"method\":\"SendMessage\",\"params\":{}}", -32602).entrySet()) {
            assertThat(one(invoker.invoke(new A2aInvocationRequest(null, error.getKey(), null)))
                    .path("error").path("code").asInt()).isEqualTo(error.getValue());
        }
        String body = rpc("SendMessage", null, "ctx", "中文");
        properties.setMaxMessageBytes(body.getBytes(StandardCharsets.UTF_8).length - 1);
        assertThat(one(invoker.invoke(new A2aInvocationRequest(null, body, null)))
                .path("error").path("message").asText()).isEqualTo("Request too large");
        properties.setMaxMessageBytes(-1);
        readiness.markAgentLoaded(false);
        var result = one(invoker.invoke(new A2aInvocationRequest(null, body, null)));
        assertThat(result.path("error").path("message").asText()).isEqualTo("Agent is not ready");
        assertThat(result.path("id").asText()).isEqualTo("rpc-id");
        assertThatThrownBy(() -> invoker.invoke(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(handler);
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void sharedAdmissionAndSynchronousFailureReturnThePermitOnce() throws Exception {
        when(gate.limit()).thenReturn(1);
        when(gate.tryAcquire()).thenReturn(false);
        String body = rpc("SendMessage", null, "ctx", "hello");
        assertThat(one(invoker.invoke(new A2aInvocationRequest(null, body, null)))
                .path("error").path("code").asInt()).isEqualTo(-32603);
        var http = new A2aJsonRpcController(dispatcher, properties);
        var request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        assertThat(http.handleJsonRpc(body, request).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(handler);
        verify(gate, never()).release();
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenThrow(new InvalidParamsError("SDK rejection"));
        assertThat(one(invoker.invoke(new A2aInvocationRequest(null, body, null)))
                .path("error").path("code").asInt()).isEqualTo(-32602);
        verify(gate, times(1)).release();
    }

    @Test
    void httpHostedObserverSeesTheSelectedTargetBeforeSdkExecution() throws Exception {
        var resolver = mock(com.openjiuwen.service.app.hosting.HostedIngressResolver.class);
        var runtime = mock(com.openjiuwen.service.app.hosting.HostedAgentRuntime.class);
        when(resolver.resolveOrDefault("a")).thenReturn(runtime);
        when(runtime.requestHandler()).thenReturn(handler);
        var selected = new java.util.concurrent.atomic.AtomicInteger();
        var servlet = new MockHttpServletRequest("POST", "/a2a/agents/a");
        servlet.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("agentId", "a"));
        com.openjiuwen.service.app.hosting.HostedIngressResolver.observeSelection(servlet, target -> {
            assertThat(target).isSameAs(runtime);
            selected.incrementAndGet();
        });
        when(handler.onMessageSend(any(), any())).thenAnswer(call -> {
            assertThat(selected).hasValue(1);
            return Task.builder().id("task").contextId("ctx")
                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
        });
        String body = rpc("SendMessage", null, "ctx", "hello");
        servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
        var http = new A2aJsonRpcController(new A2aJsonRpcDispatcher(() -> handler,
                beans.getBeanProvider(TaskAdmissionGate.class), properties, resolver), properties);
        assertThat(http.handleJsonRpc(body, servlet).getStatusCode().value()).isEqualTo(200);
        verify(resolver, times(1)).resolveOrDefault("a");
        assertThat(selected).hasValue(1);
    }

    @Test
    void methodAndHttpPreserveSerializerIdAndWrappers() throws Exception {
        Task task = Task.builder().id("task").contextId("ctx")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
        when(handler.onMessageSend(any(), any())).thenReturn(task);
        when(handler.onGetTask(any(), any())).thenReturn(task);
        var http = new A2aJsonRpcController(dispatcher, properties);
        for (String method : new String[] {"SendMessage", "GetTask"}) {
            for (String id : new String[] {"\"rpc-id\"", "42", "null", "absent"}) {
                String body = rpc(method, "task", "ctx", "hello");
                body = id.equals("absent") ? body.replace("\"id\":\"rpc-id\",", "")
                        : body.replace("\"id\":\"rpc-id\"", "\"id\":" + id);
                var request = new MockHttpServletRequest("POST", "/a2a");
                request.setContent(body.getBytes(StandardCharsets.UTF_8));
                String expected = (String) http.handleJsonRpc(body, request).getBody();
                assertThat(EmbeddedA2aInvocationTest.collect(invoker.invoke(
                        new A2aInvocationRequest(null, body, Map.of())))).containsExactly(expected);
            }
        }
        assertThat(one(invoker.invoke(new A2aInvocationRequest("explicit", rpc("GetTask", "task", null, ""), null)))
                .path("error").path("code").asInt()).isEqualTo(-32602);
    }
}
