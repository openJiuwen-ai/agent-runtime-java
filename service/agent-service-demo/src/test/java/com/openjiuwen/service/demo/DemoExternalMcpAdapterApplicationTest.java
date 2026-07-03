/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests demo application wiring with external MCP adapter registration.
 *
 * @since 2026-06-24
 */
@SpringBootTest(classes = {DemoAgentApplication.class,
        DemoExternalMcpAdapterApplicationTest.ExternalAdapterTestConfig.class}, properties = {
                "openjiuwen.demo.llm.enabled=true", "openjiuwen.demo.llm.provider=DemoExternalMcpProvider",
                "openjiuwen.demo.llm.api-key=test-key", "openjiuwen.demo.llm.api-base=mirror://demo-external-mcp",
                "openjiuwen.demo.llm.model-name=test-model", "openjiuwen.demo.llm.auto-discover=false",
                "openjiuwen.service.external.mcp.servers[0].server-id=demo-mcp",
                "openjiuwen.service.external.mcp.servers[0].server-name=demo-tools",
                "openjiuwen.service.external.mcp.servers[0].server-path=http://127.0.0.1:8999/mcp",
                "openjiuwen.service.external.mcp.servers[0].client-type=sse",
                "openjiuwen.service.external.mcp.timeout-ms=1500", "openjiuwen.service.external.mcp.retry.max=1"})
class DemoExternalMcpAdapterApplicationTest {
    @Autowired
    private AgentHandler agentHandler;

    @Autowired
    private RecordingExternalSvcAdapterRegistrar registrar;

    @BeforeAll
    static void resetRunnerBeforeContextStarts() {
        new JiuwenCoreAgentHandler("demo-reset").stop();
        Runner.stop();
    }

    @AfterEach
    void stopRunner() {
        agentHandler.stop();
        Runner.stop();
    }

    @Test
    void demoLlmHandlerRegistersExternalMcpAdaptersOnStart() {
        agentHandler.start();

        assertThat(registrar.registerToCalls).isZero();
        assertThat(registrar.registerToRunnerCalls).isEqualTo(1);
    }

    @TestConfiguration
    static class ExternalAdapterTestConfig {
        @Bean
        @Primary
        RecordingExternalSvcAdapterRegistrar recordingExternalSvcAdapterRegistrar() {
            return new RecordingExternalSvcAdapterRegistrar();
        }
    }

    static class RecordingExternalSvcAdapterRegistrar implements ExternalSvcAdapterRegistrar {
        private int registerToCalls;
        private int registerToRunnerCalls;

        @Override
        public void registerTo(RunnerConfig runnerConfig) {
            registerToCalls++;
        }

        @Override
        public void registerToRunner() {
            registerToRunnerCalls++;
        }
    }
}
