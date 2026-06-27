package com.openjiuwen.a2a;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent A → Agent B A2A scenario with stub agents (no LLM dependency).
 *
 * Starts both agents, then verifies:
 * - Agent cards are reachable
 * - A2A SendMessage returns valid JSON-RPC responses
 */
class A2aInterruptScenarioTest {

    private static ConfigurableApplicationContext agentA;
    private static ConfigurableApplicationContext agentB;

    private static final RestClient client = RestClient.create();
    private static String urlA;
    private static String urlB;

    @BeforeAll
    static void startAgents() {
        // Agent B on random port (-- overrides profile yaml's server.port)
        agentB = new SpringApplicationBuilder(AgentBApp.class)
                .sources(StubAgentTestConfig.class)
                .profiles("agent-b")
                .run("--server.port=0");
        int portB = Integer.parseInt(agentB.getEnvironment().getProperty("local.server.port"));
        urlB = "http://localhost:" + portB;

        // Agent A on random port, with Agent B's URL dynamic
        agentA = new SpringApplicationBuilder(AgentAApp.class)
                .sources(StubAgentTestConfig.class)
                .profiles("agent-a")
                .run("--server.port=0",
                        "--openjiuwen.service.a2a.remote-agents[0].url=" + urlB + "/a2a/");
        int portA = Integer.parseInt(agentA.getEnvironment().getProperty("local.server.port"));
        urlA = "http://localhost:" + portA;
    }

    @AfterAll
    static void stopAgents() {
        if (agentA != null) agentA.close();
        if (agentB != null) agentB.close();
    }

    @Test
    void agentACardIsReachable() {
        var card = client.get()
                .uri(urlA + "/.well-known/agent-card.json")
                .retrieve().body(String.class);
        assertThat(card).contains("AgentA");
    }

    @Test
    void agentBCardIsReachable() {
        var card = client.get()
                .uri(urlB + "/.well-known/agent-card.json")
                .retrieve().body(String.class);
        assertThat(card).contains("AgentB");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentARespondsToSendMessage() {
        var resp = client.post()
                .uri(urlA + "/a2a/")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "jsonrpc", "2.0", "id", 1, "method", "SendMessage",
                        "params", Map.of("message", Map.of(
                                "role", "ROLE_USER",
                                "contextId", "ctx-a",
                                "parts", List.of(Map.of("text", "Hello!"))))))
                .retrieve().body(String.class);
        assertThat(resp).contains("\"jsonrpc\"");
        assertThat(resp).contains("\"id\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentBRespondsToSendMessage() {
        var resp = client.post()
                .uri(urlB + "/a2a/")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "jsonrpc", "2.0", "id", 1, "method", "SendMessage",
                        "params", Map.of("message", Map.of(
                                "role", "ROLE_USER",
                                "contextId", "ctx-b",
                                "parts", List.of(Map.of("text", "Hello!"))))))
                .retrieve().body(String.class);
        assertThat(resp).contains("\"jsonrpc\"");
        assertThat(resp).contains("\"id\"");
    }
}
