/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Tests callback destination rejection at ingress and immediately before delivery. */
class CallbackDestinationSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1", "2130706433", "localhost", "0.0.0.0", "0.1.2.3",
        "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254",
        "100.100.100.200", "224.0.0.1", "240.0.0.1", "[::]", "[::1]", "[::ffff:127.0.0.1]",
        "[fe80::1]", "[fc00::1]", "[fd00::1]", "[fec0::1]", "[ff02::1]"})
    void rejectsNonPublicDestinationsWithoutPosting(String host) {
        assertRejectedBySender("http://" + host + "/callback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/callback", "https://8.8.8.8:8443/callback?event=done",
        "https://[2606:4700:4700::1111]/callback", "http://172.32.0.1/callback"})
    void acceptsPublicDestinations(String url) {
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri(url, List.of())).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/callback", "/callback", "ftp://127.0.0.1/callback",
        "http://secret@127.0.0.1/callback", "http://127.0.0.1/callback#fragment", "http://127.0.0.1:0/callback",
        "http://127.0.0.1:65536/callback"})
    void allowlistDoesNotBypassUrlValidation(String url) {
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri(url, List.of("127.0.0.1"))).isEmpty();
    }

    @Test
    void allowlistMatchesExactHostOnly() {
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://127.0.0.1:8080/callback",
            List.of("127.0.0.1"))).isPresent();
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("https://callback.internal/callback",
            List.of("CALLBACK.INTERNAL"))).isPresent();
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://127.0.0.2/callback",
            List.of("127.0.0.1"))).isEmpty();
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://127.0.0.1/callback",
            List.of("*", "http://127.0.0.1", "127.0.0.*"))).isEmpty();
    }

    @Test
    void rejectsMixedDnsAnswersAndRechecksBeforeDelivery() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
        String url = "https://callback.example/callback";
        try (MockedStatic<InetAddress> dns = mockStatic(InetAddress.class)) {
            dns.when(() -> InetAddress.getAllByName("callback.example"))
                .thenReturn(new InetAddress[] {publicAddress})
                .thenReturn(new InetAddress[] {publicAddress, privateAddress});
            A2aPushNotificationCallbackUrlPolicy.validateCallbackUrl(config(url), List.of());
            assertRejectedBySender(url);
        }
    }

    @Test
    void rejectsUnresolvableHostnameWithoutPosting() {
        try (MockedStatic<InetAddress> dns = mockStatic(InetAddress.class)) {
            dns.when(() -> InetAddress.getAllByName("callback.invalid")).thenThrow(new UnknownHostException());
            assertRejectedBySender("https://callback.invalid/callback");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendMessage", "SendStreamingMessage"})
    void rejectsInlineMetadataEndpointBeforeHandler(String method) {
        RequestHandler handler = mock(RequestHandler.class);
        A2aJsonRpcController controller = new A2aJsonRpcController(handler);
        A2AProperties properties = new A2AProperties();
        properties.setCallbackAllowedHosts(List.of("127.0.0.1"));
        controller.setA2aProperties(properties);
        String request = new Gson().toJson(Map.of("jsonrpc", "2.0", "id", "request-1", "method", method,
            "params", Map.of("message", Map.of("role", "ROLE_USER", "messageId", "message-1",
                "parts", List.of(Map.of("kind", "text", "text", "hello"))), "pushNotificationConfig",
                Map.of("id", "push-1", "callbackUrl", "http://169.254.169.254/latest/meta-data/"))));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/a2a");
        servletRequest.setContent(request.getBytes(StandardCharsets.UTF_8));

        Object body = controller.handleJsonRpc(request, servletRequest).getBody();

        assertThat(JsonParser.parseString((String) body).getAsJsonObject()
            .getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32602);
        verifyNoInteractions(handler);
    }

    private void assertRejectedBySender(String url) {
        InMemoryPushNotificationConfigStore store = new InMemoryPushNotificationConfigStore();
        TaskPushNotificationConfig config = config(url);
        store.setInfo(config);
        HttpClient client = mock(HttpClient.class);
        HttpPushNotificationSender sender = new HttpPushNotificationSender(store, client);
        Task task = Task.builder().id("task-1").contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();

        sender.sendNotification(task, task);

        verifyNoInteractions(client);
        assertThat(sender.deliveryRecord(sender.notificationIdFor(task, config))).hasValueSatisfying(record -> {
            assertThat(record.isSuccess()).isFalse();
            assertThat(record.message()).isEqualTo("invalid callback URL");
        });
    }

    private TaskPushNotificationConfig config(String url) {
        return TaskPushNotificationConfig.builder().id("push-1").taskId("task-1").url(url).build();
    }
}
