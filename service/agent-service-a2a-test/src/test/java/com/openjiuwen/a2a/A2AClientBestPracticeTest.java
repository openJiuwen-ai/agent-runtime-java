/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */


package com.openjiuwen.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Validates the A2A Java SDK client best practices against a running Agent B.
 * Self-contained — starts Agent B on a random port, stops it after all tests.
 *
 * <p>Follows the official {@code a2a-java/examples/helloworld/client} pattern:
 * <ol>
 *   <li>{@link A2ACardResolver} → fetch {@link AgentCard}</li>
 *   <li>{@code Client.builder(card).withTransport(JSONRPCTransport.class,
 *       new JSONRPCTransportConfig()).build()}</li>
 *   <li>{@link A2A#toUserMessage(String)} → create message</li>
 *   <li>{@link Client#sendMessage(Message)} → send and handle via consumers</li>
 * </ol>
 */
class A2AClientBestPracticeTest {

    private static final Logger log = LoggerFactory.getLogger(A2AClientBestPracticeTest.class);
    private static ConfigurableApplicationContext agentB;
    private static String agentBBaseUrl;
    private static AgentCard agentCard;

    @BeforeAll
    static void startAgentB() throws Exception {
        agentB = new SpringApplicationBuilder(AgentBApp.class)
                .sources(StubAgentTestConfig.class)
                .profiles("agent-b")
                .run("--server.port=0");
        int port = Integer.parseInt(agentB.getEnvironment().getProperty("local.server.port"));
        agentBBaseUrl = "http://localhost:" + port + "/a2a/";

        // Use HTTP/1.1 HttpClient — JdkA2AHttpClient defaults to HTTP_2
        // which fails POST through embedded Tomcat on localhost.
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var httpClient = new org.a2aproject.sdk.client.http.JdkA2AHttpClient(http11);
        agentCard = A2ACardResolver.builder()
                .baseUrl(agentBBaseUrl)
                .httpClient(httpClient)
                .build()
                .getAgentCard();
        log.info("AgentCard fetched: " + agentCard.name());
        log.info("  URL: " + agentCard.url());
        agentCard.supportedInterfaces().forEach(iface ->
                log.info("  Interface: " + iface.protocolBinding() + " → " + iface.url()));
    }

    @AfterAll
    static void stopAgentB() {
        if (agentB != null) agentB.close();
    }

    @Test
    void jdkA2AClientPostTest() throws Exception {
        // Test JdkA2AHttpClient POST directly (bypassing JSONRPCTransport)
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var jdkClient = new org.a2aproject.sdk.client.http.JdkA2AHttpClient(http11);
        var postBuilder = jdkClient.createPost()
                .url(agentBBaseUrl)
                .addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\"," +
                       "\"params\":{\"message\":{\"role\":\"ROLE_USER\"," +
                       "\"parts\":[{\"text\":\"hello\"}]}}}");
        var response = postBuilder.post();
        log.info("Status: " + response.status());
        log.info("Body: " + response.body().substring(0, Math.min(300, response.body().length())));
        assertThat(response.success()).isTrue();
    }

    @Test
    void jsonRpcBodyFormatTest() throws Exception {
        // Verify the JSON-RPC body that JSONRPCTransport sends
        var msg = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(java.util.UUID.randomUUID().toString())
                .parts(List.<Part<?>>of(new TextPart("hello")))
                .build();
        var params = MessageSendParams.builder().message(msg).build();
        var protoReq = ProtoUtils.ToProto.sendMessageRequest(params);
        // Correct parameter order: (requestId, method, payload)
        String body = JSONRPCUtils.toJsonRPCRequest(
                null, "SendMessage", protoReq);
        log.info("JSON-RPC body: " + body);

        // Post this exact body directly
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(agentBBaseUrl))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http11.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        log.info("Status: " + response.statusCode());
        log.info("Body: " + response.body().substring(0,
                Math.min(200, response.body().length())));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void providerCreateTransportTest() throws Exception {
        // Test JSONRPCTransportProvider.create() directly
        // (bypasses ClientBuilder.wrap() which may apply wrappers)
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var jdkClient = new org.a2aproject.sdk.client.http.JdkA2AHttpClient(http11);
        var provider = new org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportProvider();
        var config = new JSONRPCTransportConfig(jdkClient);
        var iface = agentCard.supportedInterfaces().get(0);
        var transport = provider.create(config, agentCard, iface);
        log.info("Transport created: " + transport.getClass().getSimpleName());

        var params = MessageSendParams.builder()
                .message(Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .contextId(java.util.UUID.randomUUID().toString())
                        .messageId(java.util.UUID.randomUUID().toString())
                        .parts(List.<Part<?>>of(new TextPart("hello")))
                        .build())
                .build();
        var result = transport.sendMessage(params, null);
        log.info("Result class: " + result.getClass().getSimpleName());
        assertThat(result instanceof Task && ((Task) result).status().state().isFinal()).isTrue();
    }

    @Test
    void compareDirectVsTransportTest() throws Exception {
        // Compare what the transport sends vs what works directly
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var jdkClient = new org.a2aproject.sdk.client.http.JdkA2AHttpClient(http11);

        var msg = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(java.util.UUID.randomUUID().toString())
                .parts(List.<Part<?>>of(new TextPart("hello")))
                .build();
        var params = MessageSendParams.builder().message(msg).build();
        var protoReq = ProtoUtils.ToProto.sendMessageRequest(params);

        // What the transport sends
        var iface = agentCard.supportedInterfaces().get(0);
        String transportUrl = org.a2aproject.sdk.util.Utils.buildBaseUrl(iface, null);
        String transportBody = JSONRPCUtils.toJsonRPCRequest(null, "SendMessage", protoReq);

        // Call via transport's JdkA2AHttpClient directly
        var postBuilder = jdkClient.createPost()
                .url(transportUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("A2A-Version", "1.0")
                .body(transportBody);
        var response = postBuilder.post();
        log.info("=== Via JdkA2AHttpClient.createPost() ===");
        log.info("URL: " + transportUrl);
        log.info("Status: " + response.status());
        log.info("Body: " + response.body().substring(0, Math.min(200, response.body().length())));
        assertThat(response.success()).isTrue();
    }

    @Test
    void buildBaseUrlTest() {
        // Verify the URL JSONRPCTransport will actually use
        var iface = agentCard.supportedInterfaces().get(0);
        log.info("Interface URL: " + iface.url());
        String builtUrl = org.a2aproject.sdk.util.Utils.buildBaseUrl(iface, null);
        log.info("buildBaseUrl result: " + builtUrl);
        // Create the exact same URI as the transport uses
        var uri = java.net.URI.create(builtUrl);
        log.info("URI: " + uri);
        log.info("  host: " + uri.getHost());
        log.info("  port: " + uri.getPort());
        log.info("  path: " + uri.getPath());
    }

    @Test
    void rawHttpClientPostTest() throws Exception {
        // Verify JDK HttpClient can POST to Agent B directly
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(agentBBaseUrl))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\"," +
                        "\"params\":{\"message\":{\"role\":\"ROLE_USER\"," +
                        "\"parts\":[{\"text\":\"hello\"}]}}}"))
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        log.info("Status: " + response.statusCode());
        log.info("Body: " + response.body().substring(0, Math.min(300, response.body().length())));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldSendMessageAndReceiveResponse() throws Exception {
        // 1. Build client per official pattern
        CompletableFuture<String> responseText = new CompletableFuture<>();
        CompletableFuture<Task> finalTask = new CompletableFuture<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add((event, card) -> {
            log.info("Received event: " + event.getClass().getSimpleName());
            if (event instanceof MessageEvent msgEvent) {
                Message msg = msgEvent.getMessage();
                StringBuilder sb = new StringBuilder();
                if (msg.parts() != null) {
                    for (Part<?> p : msg.parts()) {
                        if (p instanceof TextPart tp) sb.append(tp.text());
                    }
                }
                responseText.complete(sb.toString());
            } else if (event instanceof TaskEvent te) {
                Task task = te.getTask();
                log.info("  Task state: " + task.status().state());
                if (task.status().state().isFinal()) {
                    finalTask.complete(task);
                    // Extract text from artifacts if not already captured
                    if (!responseText.isDone() && task.artifacts() != null
                            && !task.artifacts().isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (var artifact : task.artifacts()) {
                            if (artifact.parts() != null) {
                                for (Part<?> p : artifact.parts()) {
                                    if (p instanceof TextPart tp) sb.append(tp.text());
                                }
                            }
                        }
                        responseText.complete(sb.toString());
                    }
                }
            }
        });

        Consumer<Throwable> errorHandler = error -> {
            log.error("Streaming error: {}", error.getMessage(), error);
            if (!responseText.isDone()) responseText.completeExceptionally(error);
            if (!finalTask.isDone()) finalTask.completeExceptionally(error);
        };

        // Use HTTP/1.1 — same pattern as raw HttpClient test that works
        java.net.http.HttpClient http11 = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var jdkClient = new org.a2aproject.sdk.client.http.JdkA2AHttpClient(http11);

        ClientConfig clientConfig = new ClientConfig.Builder()
                .setStreaming(false)
                .build();
        Client client = Client.builder(agentCard)
                .clientConfig(clientConfig)
                .addConsumers(consumers)
                .streamingErrorHandler(errorHandler)
                .withTransport(JSONRPCTransport.class,
                        new JSONRPCTransportConfig(jdkClient))
                .build();
        log.info("Client built successfully");

        // 2. Send message per official pattern (with contextId)
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(java.util.UUID.randomUUID().toString())
                .parts(List.<Part<?>>of(new TextPart("Find hotels in Beijing")))
                .build();
        log.info("Sending message...");
        client.sendMessage(message);
        log.info("Message sent, waiting for response...");

        // 3. Wait for response
        String text = responseText.get(30, TimeUnit.SECONDS);
        log.info("Response: " + text);

        assertThat(text).isNotBlank();
        log.info("=== TEST PASSED ===");
    }
}
