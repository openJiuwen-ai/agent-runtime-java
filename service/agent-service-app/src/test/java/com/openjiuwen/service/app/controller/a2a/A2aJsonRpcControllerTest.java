/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Tests A2A JSON-RPC request parsing and routing at the protocol boundary.
 *
 * @since 0.1.0
 */
class A2aJsonRpcControllerTest {
    @Test
    void parsesParamsAndMessageMetadataAsDistinctMaps() {
        JsonObject request = requestWithMetadata("{\"request-scope\":\"params\"}",
                "{\"request-scope\":\"message\",\"trace-id\":\"trace-1\"}");

        MessageSendParams params = A2aJsonRpcParamsParser.parseMessageSendParams(request);

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("request-scope", "params"));
        assertThat(params.message().metadata()).containsEntry("request-scope", "message").containsEntry("trace-id",
                "trace-1");
    }

    @Test
    void rejectsNonObjectMetadataAtEitherProtocolLevel() {
        assertThatThrownBy(() -> A2aJsonRpcParamsParser.parseMessageSendParams(requestWithMetadata("[]", "{}")))
                .isInstanceOf(InvalidParamsError.class);
        assertThatThrownBy(() -> A2aJsonRpcParamsParser.parseMessageSendParams(requestWithMetadata("{}", "[]")))
                .isInstanceOf(InvalidParamsError.class);
    }

    @Test
    void parsesSubscribeToTaskParams() {
        JsonObject request = JsonParser.parseString("{\"params\":{\"id\":\"task-1\",\"tenant\":\"tenant-1\"}}")
                .getAsJsonObject();

        TaskIdParams params = A2aJsonRpcParamsParser.parseTaskIdParams(request);

        assertThat(params.id()).isEqualTo("task-1");
        assertThat(params.tenant()).isEqualTo("tenant-1");
    }

    @Test
    void rejectsSubscribeToTaskWithoutTaskId() {
        JsonObject request = JsonParser.parseString("{\"params\":{\"tenant\":\"tenant-1\"}}").getAsJsonObject();

        assertThatThrownBy(() -> A2aJsonRpcParamsParser.parseTaskIdParams(request))
                .isInstanceOf(InvalidParamsError.class);
    }

    @Test
    void routesSubscribeToTaskToRequestHandlerAndReturnsSse() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long numberOfItems) {
                // No event is needed to verify protocol routing.
            }

            @Override
            public void cancel() {
                // No resource is held by this test publisher.
            }
        });
        when(requestHandler.onSubscribeToTask(any(TaskIdParams.class), any())).thenReturn(publisher);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\""
                + A2AMethods.SUBSCRIBE_TO_TASK_METHOD
                + "\",\"params\":{\"id\":\"task-1\",\"tenant\":\"tenant-1\"}}";

        ResponseEntity<?> response = controller.handleJsonRpc(request, servletRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
        ArgumentCaptor<TaskIdParams> paramsCaptor = ArgumentCaptor.forClass(TaskIdParams.class);
        verify(requestHandler).onSubscribeToTask(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue().id()).isEqualTo("task-1");
        assertThat(paramsCaptor.getValue().tenant()).isEqualTo("tenant-1");
    }

    private static JsonObject requestWithMetadata(String paramsMetadata, String messageMetadata) {
        String json = "{\"params\":{\"metadata\":" + paramsMetadata + ",\"message\":{"
                + "\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}],\"metadata\":" + messageMetadata + "}}}";
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
