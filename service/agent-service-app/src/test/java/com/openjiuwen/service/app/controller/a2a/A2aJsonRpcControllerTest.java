/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tests A2A JSON-RPC request parsing and routing at the protocol boundary.
 *
 * @since 0.1.0
 */
class A2aJsonRpcControllerTest {
    private static final String[] UNSUPPORTED_PUSH_CRUD_METHODS = {"CreateTaskPushNotificationConfig",
            "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs",
            "DeleteTaskPushNotificationConfig"};

    @Test
    void oversizedContentLengthIsRejectedWith413BeforeJsonParsing() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        A2AProperties properties = new A2AProperties();
        properties.setMaxMessageBytes(1024);
        controller.setA2aProperties(properties);
        jakarta.servlet.http.HttpServletRequest servletRequest = mock(
                jakarta.servlet.http.HttpServletRequest.class);
        when(servletRequest.getContentLengthLong()).thenReturn(2048L);

        ResponseEntity<?> response = controller.handleJsonRpc(
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"SendMessage\",\"params\":{}}",
                servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(requestHandler);
    }

    @Test
    void missingContentLengthIsRejectedWith413BeforeJsonParsing() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        A2AProperties properties = new A2AProperties();
        properties.setMaxMessageBytes(1024);
        controller.setA2aProperties(properties);
        jakarta.servlet.http.HttpServletRequest servletRequest = mock(
                jakarta.servlet.http.HttpServletRequest.class);
        when(servletRequest.getContentLengthLong()).thenReturn(-1L);

        ResponseEntity<?> response = controller.handleJsonRpc(
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"SendMessage\",\"params\":{}}",
                servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(requestHandler);
    }

    @Test
    void negativeMaxMessageBytesDisablesContentLengthPrecheck() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        when(requestHandler.onMessageSend(org.mockito.ArgumentMatchers.any(MessageSendParams.class),
                org.mockito.ArgumentMatchers.any(ServerCallContext.class))).thenReturn(completedTask());
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        A2AProperties properties = new A2AProperties();
        properties.setMaxMessageBytes(-1);
        controller.setA2aProperties(properties);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");

        ResponseEntity<?> response = controller.handleJsonRpc("""
                {
                 "jsonrpc": "2.0",
                 "id": "req-1",
                 "method": "SendMessage",
                 "params": {
                  "message": {
                   "role": "ROLE_USER",
                   "parts": [{"text": "hello"}]
                  }
                 }
                }
                """, servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(requestHandler).onMessageSend(org.mockito.ArgumentMatchers.any(MessageSendParams.class),
                org.mockito.ArgumentMatchers.any(ServerCallContext.class));
    }

    @Test
    void parsesParamsAndMessageMetadataAsDistinctMaps() {
        JsonObject request = requestWithMetadata("{\"request-scope\":\"params\"}",
                "{\"request-scope\":\"message\",\"trace-id\":\"trace-1\"}");

        MessageSendParams params = A2aJsonRpcParamsParser.parseMessageSendParams(request);

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("request-scope", "params"));
        assertThat(params.message().metadata()).containsEntry("request-scope", "message")
                .containsEntry("trace-id", "trace-1");
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
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> subscriber
                .onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long numberOfItems) {
                        // no event is needed to verify protocol routing
                    }

                    @Override
                    public void cancel() {
                        // no resource is held by this test publisher
                    }
                });
        when(requestHandler.onSubscribeToTask(any(TaskIdParams.class), any())).thenReturn(publisher);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\""
                + A2AMethods.SUBSCRIBE_TO_TASK_METHOD
                + "\",\"params\":{\"id\":\"task-1\",\"tenant\":\"tenant-1\"}}";
        servletRequest.setContent(request.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response = controller.handleJsonRpc(request, servletRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
        ArgumentCaptor<TaskIdParams> paramsCaptor = ArgumentCaptor.forClass(TaskIdParams.class);
        verify(requestHandler).onSubscribeToTask(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue().id()).isEqualTo("task-1");
        assertThat(paramsCaptor.getValue().tenant()).isEqualTo("tenant-1");
    }

    @Test
    void subscribeThrowingInPublisherSubscribeCompletesEmitterWithError() throws Exception {
        RequestHandler requestHandler = mock(RequestHandler.class);
        RejectedExecutionException rejection = new RejectedExecutionException("consumer pool exhausted");
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> {
            throw rejection;
        };
        when(requestHandler.onSubscribeToTask(any(TaskIdParams.class), any())).thenReturn(publisher);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\""
                + A2AMethods.SUBSCRIBE_TO_TASK_METHOD
                + "\",\"params\":{\"id\":\"task-1\",\"tenant\":\"tenant-1\"}}";
        // the /a2a endpoint pre-checks Content-Length before JSON
        // parsing; mirror a well-formed client by setting the request body (which
        // also sets Content-Length on the mock), otherwise the 413 guard rejects
        // the call before it reaches the subscribe routing under test here.
        servletRequest.setContent(request.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response = controller.handleJsonRpc(request, servletRequest);

        assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
        SseEmitter emitter = response.getBody() instanceof SseEmitter sseEmitter ? sseEmitter
                : throwEmitterTypeMismatch();
        // The completion callback runs asynchronously on commonPool; wait until the
        // whenComplete handler has marked the emitter complete (bounded wait).
        long deadline = System.currentTimeMillis() + 5000;
        IllegalStateException sendFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                emitter.send(SseEmitter.event().data("probe"));
                Thread.sleep(20);
            } catch (IllegalStateException completed) {
                sendFailure = completed;
                break;
            }
        }
        assertThat(sendFailure).as("emitter must be completed with error after synchronous subscribe failure")
                .isNotNull();
    }

    private static SseEmitter throwEmitterTypeMismatch() {
        throw new AssertionError("streaming response body must be an SseEmitter");
    }

    @Test
    void serializesTextWithoutHtmlSafeUnicodeEscapes() throws Exception {
        String json = A2aJsonRpcController.serializeA2aJson(new TextPart("claim=WF-001; decision=approved"));

        assertThat(json).contains("claim=WF-001; decision=approved").doesNotContain("\\u003d");
    }

    @Test
    void serializesStructuredChunksAsJsonDataInsteadOfEscapedText() throws Exception {
        String json = A2aJsonRpcController.serializeA2aJson(
                new DataPart(Map.of("type", "llm_output", "payload", Map.of("content", "working"))));
        JsonObject part = JsonParser.parseString(json).getAsJsonObject();

        assertThat(part.has("text")).isFalse();
        assertThat(part.getAsJsonObject("data").get("type").getAsString()).isEqualTo("llm_output");
        assertThat(part.getAsJsonObject("data").getAsJsonObject("payload").get("content").getAsString())
                .isEqualTo("working");
    }

    @Test
    void structuredPartRoundTripsThroughStandardA2aSdkJsonMapper() throws Exception {
        String json = A2aJsonRpcController.serializeA2aJson(
                new DataPart(Map.of("type", "llm_output", "payload", Map.of("content", "working"))));

        Part<?> decoded = JsonUtil.fromJson(json, Part.class);

        assertThat(decoded).isInstanceOfSatisfying(DataPart.class, part -> {
            assertThat(part.data()).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) part.data()).get("type")).isEqualTo("llm_output");
        });
    }

    @Test
    void unsupportedPushCrudMethodsReturnMethodNotFound() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");

        for (String method : UNSUPPORTED_PUSH_CRUD_METHODS) {
            String request = """
                    {"jsonrpc":"2.0","id":"req-1","method":"%s","params":{}}
                    """.formatted(method);
            servletRequest.setContent(request.getBytes(StandardCharsets.UTF_8));
            ResponseEntity<?> response = controller.handleJsonRpc(request, servletRequest);

            JsonObject body = jsonBody(response);
            assertThat(body.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32601);
            assertThat(body.getAsJsonObject("error").get("message").getAsString()).contains(method);
        }
        verifyNoInteractions(requestHandler);
    }

    @Test
    void absoluteHttpInlinePushConfigReachesSdkHandler() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        when(requestHandler.onMessageSend(org.mockito.ArgumentMatchers.any(MessageSendParams.class),
                org.mockito.ArgumentMatchers.any(ServerCallContext.class))).thenReturn(completedTask());
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        servletRequest.setContent(new byte[] {'{', '}'});

        controller.handleJsonRpc("""
                {
                 "jsonrpc": "2.0",
                 "id": "req-1",
                 "method": "SendMessage",
                 "params": {
                  "message": {
                   "role": "ROLE_USER",
                   "messageId": "msg-1",
                   "contextId": "ctx-1",
                   "parts": [{"kind": "text", "text": "hello"}]
                  },
                  "pushNotificationConfig": {
                   "id": "push-1",
                   "callbackUrl": "https://evil.example/a2a/push-notifications/callback"
                  }
                 }
                }
                """, servletRequest);

        verify(requestHandler).onMessageSend(org.mockito.ArgumentMatchers.any(MessageSendParams.class),
                org.mockito.ArgumentMatchers.any(ServerCallContext.class));
    }

    @Test
    void nonHttpInlinePushConfigIsRejectedBeforeCallingSdkHandler() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        A2aJsonRpcController controller = new A2aJsonRpcController(requestHandler);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        servletRequest.setContent(new byte[] {'{', '}'});

        ResponseEntity<?> response = controller.handleJsonRpc("""
                {
                 "jsonrpc": "2.0",
                 "id": "req-1",
                 "method": "SendMessage",
                 "params": {
                  "message": {
                   "role": "ROLE_USER",
                   "messageId": "msg-1",
                   "contextId": "ctx-1",
                   "parts": [{"kind": "text", "text": "hello"}]
                  },
                  "pushNotificationConfig": {
                   "id": "push-1",
                   "callbackUrl": "ftp://callback.example/a2a/push-notifications/callback"
                  }
                 }
                }
                """, servletRequest);

        JsonObject body = jsonBody(response);
        assertThat(body.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32602);
        verifyNoInteractions(requestHandler);
    }

    @Test
    void parsesInlinePushNotificationConfigIntoSdkConfiguration() {
        JsonObject request = JsonParser.parseString("""
                {
                 "params": {
                  "message": {
                   "role": "ROLE_USER",
                   "messageId": "msg-1",
                   "contextId": "ctx-1",
                   "parts": [{"kind": "text", "text": "hello"}]
                  },
                  "pushNotificationConfig": {
                   "id": "push-1",
                   "callbackUrl": "https://caller.example/a2a/push-notifications/callback",
                   "authentication": {
                    "scheme": "Bearer",
                    "credentials": "token-ref"
                   }
                  }
                 }
                }
                """).getAsJsonObject();

        MessageSendParams params = A2aJsonRpcParamsParser.parseMessageSendParams(request);

        assertThat(params.configuration()).isNotNull();
        assertThat(params.configuration().returnImmediately()).isTrue();
        assertThat(params.configuration().taskPushNotificationConfig()).satisfies(config -> {
            assertThat(config.id()).isEqualTo("push-1");
            assertThat(config.url()).isEqualTo("https://caller.example/a2a/push-notifications/callback");
            assertThat(config.authentication().scheme()).isEqualTo("Bearer");
            assertThat(config.authentication().credentials()).isEqualTo("token-ref");
        });
    }

    @Test
    void rejectsMalformedInlinePushNotificationConfigAsInvalidParams() {
        JsonObject request = JsonParser.parseString("""
                {
                 "params": {
                  "message": {
                   "role": "ROLE_USER",
                   "messageId": "msg-1",
                   "contextId": "ctx-1",
                   "parts": [{"kind": "text", "text": "hello"}]
                  },
                  "pushNotificationConfig": "not-an-object"
                 }
                }
                """).getAsJsonObject();

        assertThatThrownBy(() -> A2aJsonRpcParamsParser.parseMessageSendParams(request))
                .isInstanceOf(InvalidParamsError.class);
    }

    private static JsonObject requestWithMetadata(String paramsMetadata, String messageMetadata) {
        String json = "{\"params\":{\"metadata\":" + paramsMetadata + ",\"message\":{"
                + "\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}],\"metadata\":" + messageMetadata
                + "}}}";
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject jsonBody(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body instanceof String text) {
            return JsonParser.parseString(text).getAsJsonObject();
        }
        throw new AssertionError("JSON-RPC response body is not a string: " + body);
    }

    private static Task completedTask() {
        return Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }
}
