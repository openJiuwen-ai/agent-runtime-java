/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.controller.probe.HealthController;
import com.openjiuwen.service.spec.dto.HealthResponse;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.junit.jupiter.api.Test;

/**
 * HealthControllerTest
 *
 * @since 2026-07-03
 */
class HealthControllerTest {
    @Test
    void blankAppNameFallsBackToAgentServiceAndUsesDefaultVersion() {
        HealthController controller = new HealthController(readiness(true, true), () -> " ", new ServiceProperties());

        HealthResponse response = controller.health();

        assertThat(response.getStatus()).isEqualTo("healthy");
        assertThat(response.getApp()).isEqualTo("agent-service");
        assertThat(response.getVersion()).isEqualTo("0.1.1");
        assertThat(response.isProcessUp()).isTrue();
        assertThat(response.isAgentLoaded()).isTrue();
    }

    private static AgentReadiness readiness(boolean processUp, boolean agentLoaded) {
        return new AgentReadiness() {
            @Override
            public boolean isProcessUp() {
                return processUp;
            }

            @Override
            public boolean isAgentLoaded() {
                return agentLoaded;
            }
        };
    }
}
