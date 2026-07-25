/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * Integration tests for externally advertised Agent Card push-notification capability.
 */
abstract class AgentCardCapabilityIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    boolean pushNotifications() throws Exception {
        Map<String, Object> card = mapper.readValue(rest.getForObject("/a2a/.well-known/agent-card.json",
                String.class), Map.class);
        Map<String, Object> capabilities = (Map<String, Object>) card.get("capabilities");
        return Boolean.TRUE.equals(capabilities.get("pushNotifications"));
    }
}

@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AgentCardCapabilityDefaultIntegrationTest extends AgentCardCapabilityIntegrationTest {
    @Test
    void defaultAgentCardDoesNotAdvertisePushNotifications() throws Exception {
        assertThat(pushNotifications()).isFalse();
    }
}

@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "openjiuwen.service.a2a.push-notifications=true")
@AutoConfigureTestRestTemplate
class AgentCardCapabilityIncompleteIntegrationTest extends AgentCardCapabilityIntegrationTest {
    @Test
    void enabledSwitchWithoutTrustPolicyDoesNotAdvertisePushNotifications() throws Exception {
        assertThat(pushNotifications()).isFalse();
    }
}

@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "openjiuwen.service.a2a.push-notifications=true",
            "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=caller.example"
        })
@AutoConfigureTestRestTemplate
class AgentCardCapabilityEnabledIntegrationTest extends AgentCardCapabilityIntegrationTest {
    @Test
    void completeCallbackRuntimeAdvertisesPushNotifications() throws Exception {
        assertThat(pushNotifications()).isTrue();
    }
}
