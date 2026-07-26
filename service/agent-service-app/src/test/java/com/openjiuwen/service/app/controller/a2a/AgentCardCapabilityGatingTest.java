/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;

import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests Agent Card push-notification capability gating.
 */
class AgentCardCapabilityGatingTest {
    @Test
    void pushNotificationsRemainDisabledWhenOperatorSwitchIsOff() {
        A2AProperties properties = properties(false);

        AgentCard card = card(properties, httpSender(), new InMemoryA2aPushNotificationCallbackStore(),
                callback -> true);

        assertThat(card.capabilities().pushNotifications()).isFalse();
    }

    @Test
    void pushNotificationsDoNotRequireDeploymentHostTrust() {
        A2AProperties properties = properties(true);

        AgentCard card = card(properties, httpSender(), new InMemoryA2aPushNotificationCallbackStore(),
                callback -> true);

        assertThat(card.capabilities().pushNotifications()).isTrue();
    }

    @Test
    void pushNotificationsRemainDisabledWithoutRecoveryHandler() {
        A2AProperties properties = properties(true);

        AgentCard card = card(properties, httpSender(), new InMemoryA2aPushNotificationCallbackStore(),
                new NoOpA2aPushNotificationCallbackHandler());

        assertThat(card.capabilities().pushNotifications()).isFalse();
    }

    @Test
    void pushNotificationsAdvertisedWhenCallbackPathComplete() {
        A2AProperties properties = properties(true);

        AgentCard card = card(properties, httpSender(), new InMemoryA2aPushNotificationCallbackStore(),
                callback -> true);

        assertThat(card.capabilities().pushNotifications()).isTrue();
    }

    @Test
    void pushNotificationsRemainDisabledForNonHttpSender() {
        A2AProperties properties = properties(true);

        AgentCard card = card(properties, mock(PushNotificationSender.class),
                new InMemoryA2aPushNotificationCallbackStore(), callback -> true);

        assertThat(card.capabilities().pushNotifications()).isFalse();
    }

    private static AgentCard card(A2AProperties properties, PushNotificationSender sender,
            A2aPushNotificationCallbackStore callbackStore, A2aPushNotificationCallbackHandler callbackHandler) {
        ServiceProperties serviceProperties = new ServiceProperties();
        serviceProperties.setVersion("1.0.0");
        A2aPushNotificationCapabilityGate gate = new A2aPushNotificationCapabilityGate(properties, sender,
                callbackStore, callbackHandler);
        AgentCardController controller = new AgentCardController(properties, identity(), serviceProperties, gate);
        return controller.getStandardCard(new MockHttpServletRequest("GET", "/.well-known/agent-card.json"));
    }

    private static A2AProperties properties(boolean isPushNotifications) {
        A2AProperties properties = new A2AProperties();
        properties.setPushNotifications(isPushNotifications);
        return properties;
    }

    private static HttpPushNotificationSender httpSender() {
        return new HttpPushNotificationSender(new InMemoryPushNotificationConfigStore());
    }

    private static AgentServiceIdentity identity() {
        return () -> "agent-card-test";
    }
}
