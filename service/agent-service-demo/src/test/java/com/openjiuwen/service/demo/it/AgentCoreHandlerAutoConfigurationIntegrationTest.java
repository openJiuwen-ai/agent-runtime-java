/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.app.lifecycle.AgentHandlerHolder;
import com.openjiuwen.service.demo.it.support.SessionEchoAgent;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1: app + agentcore adapter assembly — pure {@code agent-id} without business {@code @Bean AgentHandler}.
 */
@SpringBootTest(classes = AgentCoreHandlerAutoConfigurationIntegrationTest.CoreAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "openjiuwen.service.agent-id=it-agent",
        "openjiuwen.service.query.webflux.enabled=false"
})
class AgentCoreHandlerAutoConfigurationIntegrationTest {

    private static final String AGENT_ID = "it-agent";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void registerAgentInResourceMgr() {
        Runner.resourceMgr().addAgent(
                AgentCard.builder().id(AGENT_ID).name(AGENT_ID).build(),
                SessionEchoAgent::new,
                null);
    }

    @AfterAll
    static void tearDownRunner() {
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
        ResponseEntity<String> resp = postQuery("/v1/query", Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello")),
                "conversation_id", "c-agent-id-it",
                "stream", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("conversation_id", "c-agent-id-it");
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
    static class CoreAgentApplication {
    }
}
