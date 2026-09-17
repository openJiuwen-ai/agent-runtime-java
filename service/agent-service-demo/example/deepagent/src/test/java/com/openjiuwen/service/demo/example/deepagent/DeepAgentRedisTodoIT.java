/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.demo.example.deepagent;

import com.openjiuwen.core.common.security.JsonUtils;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.extensions.checkpointer.redis.RedisCheckpointer;
import com.openjiuwen.harness.tools.KvTodoStorage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import redis.clients.jedis.Jedis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in HTTP E2E: the real model must call Todo tools; Redis is independently observed. */
@Tag("system-test")
@EnabledIfEnvironmentVariable(named = "OPENJIUWEN_TODO_REDIS_E2E", matches = "true")
class DeepAgentRedisTodoIT {
    @Test
    @Timeout(300)
    void runtimeConfigAlonePersistsCheckpointsAndModelCreatedTodoWithTtl() throws Exception {
        String session = "runtime-todo-e2e-" + UUID.randomUUID();
        String marker = "persist-" + UUID.randomUUID();
        String host = System.getenv().getOrDefault("REDIS_IP", "127.0.0.1");
        String port = System.getenv().getOrDefault("REDIS_PORT", "6379");
        String password = System.getenv().getOrDefault("REDIS_PASSWORD", "");
        SpringApplication application = new SpringApplication(DeepAgentDemoApplication.class);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testRedisCredentials", Map.of(
                        "openjiuwen.service.middleware.redis.default.encrypted-password", password))));
        try (Jedis observer = new Jedis(host, Integer.parseInt(port), 5000)) {
            if (!password.isBlank()) {
                observer.auth(password);
            }
            assertEquals("PONG", observer.ping());
            try (ConfigurableApplicationContext context = application.run(
                    "--server.port=0", "--demo.deepagent.todo-enabled=true",
                    "--openjiuwen.service.middleware.checkpointer.type=redis",
                    "--openjiuwen.service.middleware.checkpointer.ttl-seconds=180",
                    "--openjiuwen.service.middleware.redis.default.host=" + host,
                    "--openjiuwen.service.middleware.redis.default.port=" + port,
                    "--logging.level.root=INFO",
                    "--openjiuwen.service.llm.max-iterations=8")) {
                int httpPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                RedisCheckpointer cp = assertInstanceOf(RedisCheckpointer.class,
                        CheckpointerFactory.getCheckpointer());
                assertEquals(Duration.ofSeconds(180), cp.getEffectiveTtl());
                HttpClient http = HttpClient.newHttpClient();
                // The demo passes an Agent instance without a middleware registrar or Todo storage settings.
                String response = query(http, httpPort, session,
                        "Call todo_create now with exactly one task whose content is '" + marker
                                + "'. Do not execute the task. Then reply with the saved task content.", false);
                assertTrue(response.contains(marker), response);
                assertTrue(observer.exists(session + ":todo"), "The model must persist a real Todo: " + response);
                assertTrue(new KvTodoStorage(cp.getRedisStore()).load(session).stream()
                        .anyMatch(todo -> marker.equals(todo.getContent())));
                assertPersistedKeysAndTtl(observer, session);

                String streamed = query(http, httpPort, session,
                        "Call todo_list to read the saved tasks, then repeat the task content exactly.", true);
                assertTrue(streamed.contains("data:"), "Expected an HTTP SSE response");
                assertTrue(streamed.contains(marker), streamed);
                assertPersistedKeysAndTtl(observer, session);
            } finally {
                Set<String> keys = observer.keys(session + ":*");
                if (!keys.isEmpty()) {
                    observer.del(keys.toArray(String[]::new));
                }
            }
        }
    }

    private static String query(HttpClient client, int port, String session, String message, boolean stream)
            throws Exception {
        String body = JsonUtils.safeJsonDumps(Map.of("conversation_id", session,
                "message", message, "stream", stream), "{}");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static void assertPersistedKeysAndTtl(Jedis observer, String session) {
        Set<String> keys = observer.keys(session + ":*");
        assertTrue(keys.contains(session + ":todo"));
        assertTrue(keys.stream().anyMatch(key -> !key.endsWith(":todo")), "Checkpoints must also be persisted");
        for (String key : keys) {
            long ttl = observer.ttl(key);
            assertTrue(ttl > 0 && ttl <= 180, () -> "Invalid inherited TTL for " + key + ": " + ttl);
        }
    }
}
