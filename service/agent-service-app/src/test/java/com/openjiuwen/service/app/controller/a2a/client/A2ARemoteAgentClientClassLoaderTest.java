/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientBuilder;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransportProvider;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression tests for A2A SDK client creation and per-agent invocation modes.
 */
class A2ARemoteAgentClientClassLoaderTest {
    @Test
    void callOutcomeReturnsBeforeBlockingSdkSendCompletes() throws Exception {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("sync-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch callReturned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean returnedBeforeRelease = new AtomicBoolean();
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);
            CompletableFuture<Void> releaser = CompletableFuture.runAsync(() -> {
                try {
                    if (entered.await(2, TimeUnit.SECONDS)) {
                        returnedBeforeRelease.set(callReturned.await(2, TimeUnit.SECONDS));
                    }
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                } finally {
                    release.countDown();
                }
            });
            A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
            CompletableFuture<RemoteCallOutcome> outcome = null;
            try {
                outcome = remoteClient.callOutcome(remoteCall("sync-agent"),
                        mock(RemoteAgentCaller.EventObserver.class));
                callReturned.countDown();
                releaser.get(3, TimeUnit.SECONDS);
                assertThat(returnedBeforeRelease).isTrue();
            } finally {
                if (outcome != null) {
                    outcome.cancel(true);
                }
                release.countDown();
                remoteClient.shutdown();
            }
        }
    }

    @Test
    void timeoutAppliesWhileNonStreamingSdkCallIsBlocked() {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("timeout-agent", card, 1, false);
        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("timeout-agent"),
                    mock(RemoteAgentCaller.EventObserver.class));

            assertThatThrownBy(() -> outcome.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
            remoteClient.shutdown();
        }
    }

    @Test
    void synchronousSdkFailureCompletesOutcomeImmediately() {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("failing-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        doAnswer(invocation -> {
            throw new A2AClientException("SDK send failed");
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("failing-agent"),
                    mock(RemoteAgentCaller.EventObserver.class));

            assertThatThrownBy(() -> outcome.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(A2AClientException.class)
                    .hasRootCauseMessage("SDK send failed");
        } finally {
            remoteClient.shutdown();
        }
    }

    @Test
    void synchronousSdkRuntimeFailureCompletesOutcomeImmediately() {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("runtime-failing-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        doThrow(new IllegalArgumentException("invalid SDK event")).when(sdkClient)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("runtime-failing-agent"),
                    mock(RemoteAgentCaller.EventObserver.class));

            assertThatThrownBy(() -> outcome.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("invalid SDK event");
        } finally {
            remoteClient.shutdown();
        }
    }

    @Test
    void directMessageEventCompletesCallWithAllTextParts() throws Exception {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("message-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        Message message = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.<Part<?>>of(new TextPart("hello "), new TextPart("world"))).build();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<java.util.function.BiConsumer<org.a2aproject.sdk.client.ClientEvent, AgentCard>> consumers = invocation
                    .getArgument(1);
            consumers.get(0).accept(new MessageEvent(message), card);
            return null;
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("message-agent"),
                    mock(RemoteAgentCaller.EventObserver.class))
                .get(2, TimeUnit.SECONDS);

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(outcome.result()).isEqualTo("hello world");
        }
    }

    @Test
    void completedTaskAggregatesAllArtifacts() throws Exception {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("task-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(
                        org.a2aproject.sdk.spec.Artifact.builder().artifactId("a").parts(new TextPart("hello "))
                                .build(),
                        org.a2aproject.sdk.spec.Artifact.builder().artifactId("b").parts(new TextPart("world"))
                                .build()))
                .build();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<java.util.function.BiConsumer<org.a2aproject.sdk.client.ClientEvent, AgentCard>> consumers = invocation
                    .getArgument(1);
            consumers.get(0).accept(new TaskEvent(task), card);
            return null;
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("task-agent"),
                    mock(RemoteAgentCaller.EventObserver.class))
                .get(2, TimeUnit.SECONDS);

            assertThat(outcome.result()).isEqualTo("hello world");
        }
    }

    @Test
    void completedTaskWithoutArtifactsUsesStatusMessage() throws Exception {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("status-agent", card, 30, false);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        Message statusMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.<Part<?>>of(new TextPart("status result"))).build();
        Task task = Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, statusMessage, null)).artifacts(List.of())
                .build();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<java.util.function.BiConsumer<org.a2aproject.sdk.client.ClientEvent, AgentCard>> consumers = invocation
                    .getArgument(1);
            consumers.get(0).accept(new TaskEvent(task), card);
            return null;
        }).when(sdkClient).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());

        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, card, builder, sdkClient);

            var outcome = remoteClient.callOutcome(remoteCall("status-agent"),
                    mock(RemoteAgentCaller.EventObserver.class))
                .get(2, TimeUnit.SECONDS);

            assertThat(outcome.result()).isEqualTo("status result");
        }
    }

    @Test
    void cardsWithSameDisplayNameButDifferentUrlsDoNotShareClient() {
        AgentCard firstCard = testCard("http://localhost:18091/a2a");
        AgentCard secondCard = testCard("http://localhost:18092/a2a");
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("first", firstCard, 30, false);
        registry.register("second", secondCard, 30, false);
        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        ClientBuilder firstBuilder = mock(ClientBuilder.class);
        ClientBuilder secondBuilder = mock(ClientBuilder.class);
        Client firstClient = mock(Client.class);
        Client secondClient = mock(Client.class);

        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            stubClient(clientFactory, firstCard, firstBuilder, firstClient);
            stubClient(clientFactory, secondCard, secondBuilder, secondClient);

            var first = remoteClient.callOutcome(remoteCall("first"),
                    mock(RemoteAgentCaller.EventObserver.class));
            var second = remoteClient.callOutcome(remoteCall("second"),
                    mock(RemoteAgentCaller.EventObserver.class));

            verify(firstBuilder).build();
            verify(secondBuilder).build();
            verify(firstClient, timeout(1000)).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());
            verify(secondClient, timeout(1000)).sendMessage(any(MessageSendParams.class), anyList(), any(), isNull());
            first.cancel(true);
            second.cancel(true);
        }
    }

    @Test
    void callOutcomeRequiresConfiguredAndCallerStreaming() throws Exception {
        AgentCard card = testCard();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("sync-agent", card, 30);
        registry.register("stream-agent", card, 30, true);
        A2ARemoteAgentClient remoteClient = new A2ARemoteAgentClient(registry);
        ClientBuilder builder = mock(ClientBuilder.class);
        Client sdkClient = mock(Client.class);
        ArgumentCaptor<MessageSendParams> params = ArgumentCaptor.forClass(MessageSendParams.class);

        try (MockedStatic<Client> clientFactory = mockStatic(Client.class)) {
            clientFactory.when(() -> Client.builder(card)).thenReturn(builder);
            when(builder.clientConfig(any(ClientConfig.class))).thenReturn(builder);
            when(builder.withTransport(eq(JSONRPCTransport.class), any(JSONRPCTransportConfig.class)))
                    .thenReturn(builder);
            when(builder.build()).thenReturn(sdkClient);

            var unconfigured = remoteClient.callOutcome(remoteCall("sync-agent", true),
                    mock(RemoteAgentCaller.EventObserver.class));
            var callerSync = remoteClient.callOutcome(remoteCall("stream-agent", false),
                    mock(RemoteAgentCaller.EventObserver.class));
            var enabled = remoteClient.callOutcome(remoteCall("stream-agent", true),
                    mock(RemoteAgentCaller.EventObserver.class));
            verify(sdkClient, timeout(1000).times(3)).sendMessage(params.capture(), anyList(), any(), isNull());
            unconfigured.cancel(false);
            callerSync.cancel(false);
            enabled.cancel(false);
        }

        ArgumentCaptor<ClientConfig> configs = ArgumentCaptor.forClass(ClientConfig.class);
        verify(builder, times(3)).clientConfig(configs.capture());
        assertThat(configs.getAllValues()).extracting(ClientConfig::isStreaming).containsExactly(false, false, true);
        assertThat(params.getAllValues()).allSatisfy(value -> {
            assertThat(value.configuration()).isNotNull();
            assertThat(value.configuration().returnImmediately()).isFalse();
        });
    }

    @Test
    void createClientUsesApplicationClassLoaderForTransportDiscovery() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new NoServicesClassLoader(original));
        try {
            A2ARemoteAgentClient client = new A2ARemoteAgentClient(new A2ARemoteAgentCardRegistry());
            RemoteAgentEntry entry = new RemoteAgentEntry("remote", testCard(), 30, true);
            Method createClient = A2ARemoteAgentClient.class.getDeclaredMethod("createClient", RemoteAgentEntry.class,
                    boolean.class);
            createClient.setAccessible(true);

            assertThatCode(() -> createClient.invoke(client, entry, true)).doesNotThrowAnyException();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static AgentCard testCard() {
        return testCard("http://localhost:8080/a2a");
    }

    private static AgentCard testCard(String url) {
        return AgentCard.builder().name("remote").description("remote").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text")).skills(List.of()).securitySchemes(Collections.emptyMap())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0"))).url(url)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    private static void stubClient(MockedStatic<Client> factory, AgentCard card, ClientBuilder builder, Client client) {
        factory.when(() -> Client.builder(card)).thenReturn(builder);
        when(builder.clientConfig(any(ClientConfig.class))).thenReturn(builder);
        when(builder.withTransport(eq(JSONRPCTransport.class), any(JSONRPCTransportConfig.class))).thenReturn(builder);
        when(builder.build()).thenReturn(client);
    }

    private static RemoteCall remoteCall(String agentName) {
        return new RemoteCall(agentName, "hello", "context", null, Map.of());
    }

    private static RemoteCall remoteCall(String agentName, boolean isCallerStreaming) {
        return new RemoteCall(agentName, "hello", "context", null, Map.of(), Map.of(), isCallerStreaming);
    }

    private static final class NoServicesClassLoader extends ClassLoader {
        private static final String TRANSPORT_PROVIDER_SERVICE = "META-INF/services/"
                + ClientTransportProvider.class.getName();

        private NoServicesClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws java.io.IOException {
            if (TRANSPORT_PROVIDER_SERVICE.equals(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }
}
