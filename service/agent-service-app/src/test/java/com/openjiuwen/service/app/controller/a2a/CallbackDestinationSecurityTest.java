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
    @Test
    void rejectsNonPublicIpv4DestinationsWithoutPosting() {
        for (String host : List.of(ipv4(127, 0, 0, 1), ipv4(0, 0, 0, 0), ipv4(0, 1, 2, 3), ipv4(10, 0, 0, 1),
            ipv4(172, 16, 0, 1), ipv4(172, 31, 255, 255), ipv4(192, 168, 0, 1), ipv4(169, 254, 169, 254),
            ipv4(100, 100, 100, 200), ipv4(224, 0, 0, 1), ipv4(240, 0, 0, 1))) {
            assertRejectedBySender("http://" + host + "/callback");
        }
    }

    @Test
    void rejectsNonPublicIpv6DestinationsWithoutPosting() {
        for (String host : List.of("[::]", "[::1]", "[::ffff:127.0.0.1]", "[fe80::1]", "[fc00::1]",
            "[fd00::1]", "[fec0::1]", "[ff02::1]")) {
            assertRejectedBySender("http://" + host + "/callback");
        }
    }

    @Test
    void rejectsLocalHostNamesWithoutPosting() {
        for (String host : List.of("localhost", "127.1", "2130706433")) {
            assertRejectedBySender("http://" + host + "/callback");
        }
    }

    @Test
    void acceptsPublicDestinations() {
        for (String url : List.of("http://" + ipv4(203, 0, 113, 10) + "/callback",
            "https://" + ipv4(203, 0, 113, 10) + ":8443/callback?event=done",
            "https://[2001:db8::1]/callback", "http://" + ipv4(172, 32, 0, 1) + "/callback")) {
            assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri(url, List.of())).isPresent();
        }
    }

    @Test
    void allowlistDoesNotBypassUrlValidation() {
        String loopback = ipv4(127, 0, 0, 1);
        for (String url : List.of("file:///tmp/callback", "/callback", "ftp://" + loopback + "/callback",
            "http://secret@" + loopback + "/callback", "http://" + loopback + "/callback#fragment",
            "http://" + loopback + ":0/callback", "http://" + loopback + ":65536/callback")) {
            assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri(url, List.of(loopback))).isEmpty();
        }
    }

    @Test
    void allowlistMatchesExactHostOnly() {
        String loopback = ipv4(127, 0, 0, 1);
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://" + loopback + ":8080/callback",
            List.of(loopback))).isPresent();
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("https://callback.internal/callback",
            List.of("CALLBACK.INTERNAL"))).isPresent();
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://" + ipv4(127, 0, 0, 2) + "/callback",
            List.of(loopback))).isEmpty();
        List<String> unsupportedHosts = List.of("*", "http://" + loopback, "127.0.0.*");
        assertThat(A2aPushNotificationCallbackUrlPolicy.callbackUri("http://" + loopback + "/callback",
            unsupportedHosts)).isEmpty();
    }

    @Test
    void rejectsMixedDnsAnswersAndRechecksBeforeDelivery() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 10});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
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

        assertThat(body).isInstanceOf(String.class);
        if (body instanceof String responseBody) {
            assertThat(JsonParser.parseString(responseBody).getAsJsonObject()
                .getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32602);
        }
        verifyNoInteractions(handler);
    }

    private static String ipv4(int first, int second, int third, int fourth) {
        return String.join(".", String.valueOf(first), String.valueOf(second), String.valueOf(third),
            String.valueOf(fourth));
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
