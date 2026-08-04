/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot test application wiring a stub {@link AgentHandler} for
 * integration tests.
 *
 * <p>Uses {@link EnableAutoConfiguration} instead of broad component scanning so
 * nested {@code @SpringBootConfiguration} classes in this test package are not
 * picked up (they may exclude lifecycle auto-configuration).
 *
 * @since 0.1.0
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestServiceApplication {
    @Bean
    @Primary
    AgentHandler echoAgentHandler() {
        return new MultiTurnEchoHandler();
    }
}
