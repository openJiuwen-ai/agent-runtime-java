/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.a2a;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that provides a stub {@link AgentHandler}, replacing the LLM-dependent auto-configured one via
 * {@code @Primary}.
 */
@TestConfiguration
class StubAgentTestConfig {

    @Bean
    @Primary
    AgentHandler stubAgentHandler() {
        return new StubAgentHandler();
    }
}
