/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demol1test.health;

import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Internal test helper: scenario-switchable health probe L1 validation app.
 */
@SpringBootApplication
public class HealthL1ProbeExample {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(HealthL1ProbeExample.class);
        application.setDefaultProperties(Map.of(
                "server.port", "8090",
                "spring.main.web-application-type", "servlet",
                "example.health.l1.mode", "normal",
                "example.health.l1.handler", "loaded"
        ));
        application.run(args);
    }

    @Bean
    DefaultAgentReadiness healthL1Readiness(
            @Value("${example.health.l1.mode:normal}") String mode) {
        DefaultAgentReadiness readiness = new DefaultAgentReadiness();
        if ("shutdown".equalsIgnoreCase(mode)) {
            readiness.markShuttingDown();
        } else if ("process-down".equalsIgnoreCase(mode)) {
            readiness.markProcessDown();
        }
        return readiness;
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.health.l1", name = "identity", havingValue = "blank")
    AgentServiceIdentity blankHealthL1Identity() {
        return () -> " ";
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.health.l1", name = "lifecycle", havingValue = "disabled")
    AgentLifecycleManager disabledHealthL1LifecycleManager() {
        return new AgentLifecycleManager() {
            @Override
            public void runInitPhase() {
                // Keep readiness in its pre-init state for H-L1-008.
            }

            @Override
            public void runShutdownPhase() {
                // No-op: this scenario only validates readiness before init.
            }

            @Override
            public void interrupt(String conversationId) {
                // No active work is started in this example.
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.health.l1", name = "handler",
            havingValue = "loaded", matchIfMissing = true)
    AgentHandler loadedHealthL1AgentHandler() {
        return queryFailingHandler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.health.l1", name = "handler", havingValue = "failing-start")
    AgentHandler failingStartHealthL1AgentHandler() {
        return new AgentHandler() {
            @Override
            public void start() {
                throw new IllegalStateException("health l1 forced start failure");
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                throw new AssertionError("/health must not call query");
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                throw new AssertionError("/health must not call streamQuery");
            }
        };
    }

    private static AgentHandler queryFailingHandler() {
        return new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest request) {
                throw new AssertionError("/health must not call query");
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                throw new AssertionError("/health must not call streamQuery");
            }
        };
    }
}
