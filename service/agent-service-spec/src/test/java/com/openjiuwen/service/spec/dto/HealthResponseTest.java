/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.paths.AgentServicePaths;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * HealthResponseTest
 *
 * @since 2026-07-03
 */
class HealthResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void serializesPythonCompatibleHealthFields() throws Exception {
        HealthResponse response = new HealthResponse("healthy", "demo-agent", "1.2.3", true, false);

        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(response), Map.class);

        assertThat(json).containsEntry("status", "healthy");
        assertThat(json).containsEntry("app", "demo-agent");
        assertThat(json).containsEntry("version", "1.2.3");
        assertThat(json).containsEntry("process_up", true);
        assertThat(json).containsEntry("agent_loaded", false);
    }

    @Test
    void exposesHealthPathConstant() {
        assertThat(AgentServicePaths.HEALTH).isEqualTo("/health");
    }
}
