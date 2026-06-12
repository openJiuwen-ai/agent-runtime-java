/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class TestServiceApplication {

    @Bean
    AgentHandler echoAgentHandler() {
        return new MultiTurnEchoHandler();
    }
}
