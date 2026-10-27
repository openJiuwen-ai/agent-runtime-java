/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.app.lifecycle.AgentHandlerHolder;
import com.openjiuwen.service.demo.it.support.SessionEchoAgent;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * AC1: app + agentcore adapter assembly - pure {@code agent-id} without
 * business {@code @Bean AgentHandler}.
 */
@SpringBootTest(classes = AgentCoreHandlerAutoConfigurationIntegrationTest.CoreAgentApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "openjiuwen.service.agent-id=it-agent", "openjiuwen.service.query.webflux.enabled=false"
})
@AutoConfigureTestRestTemplate
class AgentCoreHandlerAutoConfigurationIntegrationTest {
    private static final String AGENT_ID = "it-agent";

    private static final String CONVERSATION_ID = "c-agent-id-it";

    static {
        // 注册必须在 Spring 上下文加载之前完成：@SpringBootTest 的上下文加载先于
        // @BeforeAll，若 agent 注册过晚，上下文加载期可能查不到 ResourceMgr 中的 agent。
        Runner.resourceMgr()
            .addAgent(AgentCard.builder().id(AGENT_ID).name(AGENT_ID).build(), SessionEchoAgent::new, null);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearConversation() {
        Runner.release(CONVERSATION_ID);
    }

    @AfterAll
    static void tearDownRunner() {
        Runner.release(CONVERSATION_ID);
        new JiuwenCoreAgentHandler(AGENT_ID).stop();
        Runner.resourceMgr().removeAgent(AGENT_ID, null, TagMatchStrategy.ALL, true);
    }

    @Test
    void autoConfigurationRegistersJiuwenCoreAgentHandlerFromAgentIdOnly() {
        assertThat(context.getBean(AgentHandler.class)).isInstanceOf(JiuwenCoreAgentHandler.class);
        assertThat(context.getBeansOfType(AgentHandlerHolder.class)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryEndpointWorksWithoutCustomHandlerBean() throws Exception {
        ResponseEntity<String> resp = postQuery("/v1/query",
            Map.of("messages", List.of(Map.of("role", "user", "content", "hello")), "conversation_id", CONVERSATION_ID,
                "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("conversation_id", CONVERSATION_ID);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("content", "turn1:hello");
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CoreAgentApplication {}
}
