/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.controller.a2a.RedisTaskStore;
import com.openjiuwen.service.spec.hosting.ScopedRuntimeRedisClient;

import redis.clients.jedis.JedisPooled;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Real Redis process integration; opt in with -Dfeat037.redis.executable=/path/to/redis-server.
 */
@EnabledIfSystemProperty(named = "feat037.redis.executable", matches = ".+")
class HostedRedisIsolationTest {
    private Path directory;

    private Process server;
    private JedisPooled jedis;
    private JedisPooledRuntimeRedisClient shared;
    private ScopedRuntimeRedisClient a;
    private ScopedRuntimeRedisClient b;
    private int port;
    private Path logFile;

    @BeforeEach
    void start() throws Exception {
        // Retain RDB/log evidence under Maven output. Windows Redis may keep mapped
        // file handles briefly after exit; these are not JUnit-managed temporary files.
        Path evidence = Path.of("target", "feat037-redis-evidence").toAbsolutePath();
        Files.createDirectories(evidence);
        directory = Files.createTempDirectory(evidence, "run-");
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        startServer();
        connect();
    }

    private void startServer() throws Exception {
        logFile = directory.resolve("redis.log");
        server = new ProcessBuilder(System.getProperty("feat037.redis.executable"), "--bind", "127.0.0.1",
                "--port", String.valueOf(port), "--dir", directory.toString(), "--dbfilename", "hosted-test.rdb",
                "--appendonly", "no", "--loglevel", "warning")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())).start();
    }

    private void connect() {
        jedis = new JedisPooled("127.0.0.1", port);
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(() -> {
            assertThat(server.isAlive()).as("Redis process; see %s", logFile).isTrue();
            assertThat(jedis.ping()).isEqualTo("PONG");
        });
        shared = new JedisPooledRuntimeRedisClient(jedis);
        a = new ScopedRuntimeRedisClient(shared, ScopedRuntimeRedisClient.namespace("hosted-test", "a"));
        b = new ScopedRuntimeRedisClient(shared, ScopedRuntimeRedisClient.namespace("hosted-test", "b"));
    }

    @AfterEach
    void stop() throws Exception {
        if (shared != null) {
            shared.close();
        }
        if (shared == null && jedis != null) {
            jedis.close();
        }
        stopServer();
    }

    private void stopServer() throws Exception {
        if (server != null && server.isAlive()) {
            try (var admin = new redis.clients.jedis.Jedis("127.0.0.1", port)) {
                admin.shutdown();
            } catch (redis.clients.jedis.exceptions.JedisConnectionException expectedOnShutdown) {
                // The server may close the admin connection before replying.
            }
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                assertThat(server.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(server.isAlive()).isFalse();
        }
    }

    @Test
    void redisKeyOperationsPreserveNamespacesTtlAndBinaryValues() {
        byte[] key = {(byte) 0xff, 0, 1};
        byte[] value = {0, (byte) 0xfe, 2};
        assertThat(a.set("same", "A")).isEqualTo("OK");
        assertThat(b.set("same", "B")).isEqualTo("OK");
        assertThat(text(a.get("same"))).contains("A");
        assertThat(text(b.get("same"))).contains("B");
        assertThat(a.set("text-binary", value)).isEqualTo("OK");
        assertThat(a.get("text-binary".getBytes(StandardCharsets.UTF_8))).containsExactly(value);
        assertThat(a.set(key, value)).isEqualTo("OK");
        assertThat(a.exists(key)).isTrue();
        assertThat(b.exists(key)).isFalse();
        assertThat(a.get(key)).containsExactly(value);
        assertThat(a.setnx("same", "overwritten")).isZero();
        assertThat(a.setnx("new", "first")).isEqualTo(1);
        assertThat(b.setnx("new", "other")).isEqualTo(1);
        assertThat(a.setnx(key, new byte[] {9})).isZero();
        assertThat(b.setnx(key, value)).isEqualTo(1);
        assertThat(a.expire("same", 30)).isEqualTo(1);
        assertThat(a.expire(key, 30)).isEqualTo(1);
        assertThat(a.mget("new", "missing", "same").stream().map(HostedRedisIsolationTest::text).toList())
                .containsExactly(Optional.of("first"), Optional.empty(), Optional.of("A"));
        assertThat(a.setex("ttl", 1, "expire")).isEqualTo("OK");
        assertThat(b.setex("ttl", 60, "keep")).isEqualTo("OK");
        byte[] ttlKey = "ttl-binary".getBytes(StandardCharsets.UTF_8);
        assertThat(a.setex(ttlKey, 1, value)).isEqualTo("OK");
        assertThat(b.setex(ttlKey, 60, value)).isEqualTo("OK");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(a.exists("ttl")).isFalse();
            assertThat(a.exists(ttlKey)).isFalse();
        });
        assertThat(text(b.get("ttl"))).contains("keep");
        assertThat(b.get(ttlKey)).containsExactly(value);
        assertThat(jedis.ttl(ScopedRuntimeRedisClient.namespace("hosted-test", "b") + "ttl"))
                .isBetween(1L, 60L);
        assertThat(a.scanIter("n*")).containsExactly("new");
        assertThat(text(a.get(a.scanIter("n*").get(0)))).contains("first");
        assertThat(a.del("same", "new")).isEqualTo(2);
        assertThat(a.del(key)).isEqualTo(1);
        assertThat(text(b.get("same"))).contains("B");
        assertThat(b.get(key)).containsExactly(value);
        a.close();
        assertThat(text(b.get("same"))).contains("B");
        assertThat(jedis.ping()).isEqualTo("PONG");
    }

    @Test
    void taskStateAndShadowsStayLocalAfterRedisRestart() throws Exception {
        TaskStore storeA = cached(a);
        TaskStore storeB = cached(b);
        storeA.save(task("same-task", "A"), true);
        storeB.save(task("same-task", "B"), true);
        storeA.save(shadow("A"), true);
        storeB.save(shadow("B"), true);
        assertThat(storeA.get("same-task").metadata()).containsEntry("owner", "A");
        assertThat(storeB.get("same-task").metadata()).containsEntry("owner", "B");
        assertContext(storeA, "A");
        assertContext(storeB, "B");
        try (var admin = new redis.clients.jedis.Jedis("127.0.0.1", port)) {
            assertThat(admin.save()).isEqualTo("OK");
        }
        shared.close();
        shared = null;
        jedis = null;
        stopServer();
        startServer();
        connect();
        storeA = cached(a);
        storeB = cached(b);
        assertContext(storeA, "A");
        assertContext(storeB, "B");
        assertThat(storeA.get("shadow:same-parent").metadata().toString()).contains("result-A");
        assertThat(storeB.get("shadow:same-parent").metadata().toString()).contains("result-B");
        storeA.delete("same-task");
        storeA.delete("shadow:same-parent");
        assertThat(storeA.list(ListTasksParams.builder().contextId("same-context").build()).tasks()).isEmpty();
        assertContext(storeB, "B");
        assertThat(storeB.get("shadow:same-parent")).isNotNull();
        assertThat(jedis.keys(ScopedRuntimeRedisClient.namespace("hosted-test", "a") + "a2a:task:*"))
                .isEmpty();
        assertThat(jedis.keys(ScopedRuntimeRedisClient.namespace("hosted-test", "b") + "a2a:task:*"))
                .hasSize(2);
    }

    @Test
    void taskExpiryDoesNotChangeAnotherInstancesTask() {
        var storeA = new RedisTaskStore(a, 1);
        var storeB = new RedisTaskStore(b, 60);
        storeA.save(task("same-task", "A"), true);
        storeB.save(task("same-task", "B"), true);
        assertContext(storeA, "A");
        assertContext(storeB, "B");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(storeA.get("same-task")).isNull());
        assertThat(storeA.list(ListTasksParams.builder().contextId("same-context").build()).tasks()).isEmpty();
        assertContext(storeB, "B");
    }

    private static TaskStore cached(ScopedRuntimeRedisClient client) {
        var middleware = new MiddlewareProperties();
        middleware.getCheckpointer().setType("redis");
        middleware.getCheckpointer().setTtlSeconds(60);
        return A2AAutoConfiguration.createTaskStore(middleware, client, new A2AProperties());
    }

    private static Optional<String> text(Object value) {
        return Optional.ofNullable(value)
                .map(bytes -> new String(assertInstanceOf(byte[].class, bytes), StandardCharsets.UTF_8));
    }

    private static void assertContext(TaskStore store, String owner) {
        assertThat(store.list(ListTasksParams.builder().contextId("same-context").build()).tasks())
                .singleElement().satisfies(task -> assertThat(task.metadata()).containsEntry("owner", owner));
    }

    private static Task task(String id, String owner) {
        return Task.builder().id(id).contextId("same-context").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .metadata(Map.of("owner", owner)).build();
    }

    private static Task shadow(String owner) {
        return Task.builder().id("shadow:same-parent").contextId("shadow-context")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                .metadata(Map.of("_remote_batch", Map.of("batchId", "same-batch", "parentTaskId", "same-parent",
                        "state", "READY_TO_RESUME", "members", List.of(Map.of("remoteTaskId", "same-remote-task",
                                "result", "result-" + owner, "state", "COMPLETED"))))).build();
    }
}
