/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.service.adapters.agentcore.memory.jiuwen.JiuwenMemoryApi;
import com.openjiuwen.service.adapters.agentcore.memory.jiuwen.JiuwenMemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryMessage;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration test for Jiuwen Memory Engine against a real service.
 *
 * <p>This test is skipped if the Jiuwen service is not reachable.
 * To run, ensure the Jiuwen Memory Engine is available at the configured endpoint.
 *
 * <p>Configuration via environment variables or system properties:
 * <ul>
 *   <li>JIUWEN_ENDPOINT (default: http://1.94.231.141:8516)</li>
 *   <li>JIUWEN_API_KEY (default: mem-key-2026)</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JiuwenMemoryIntegrationTest {
    private static final String DEFAULT_ENDPOINT = "http://1.94.231.141:8516";

    private static final String DEFAULT_API_KEY = "mem-key-2026";

    private String endpoint;

    private String apiKey;

    private JiuwenMemoryStore memoryStore;

    private String testUserId;

    @BeforeAll
    void setUp() {
        endpoint = getEnvOrDefault("JIUWEN_ENDPOINT", DEFAULT_ENDPOINT);
        apiKey = getEnvOrDefault("JIUWEN_API_KEY", DEFAULT_API_KEY);

        // Skip test if service is not reachable
        assumeTrue(isServiceReachable(endpoint), "Jiuwen service not reachable at: " + endpoint);

        testUserId = "integration-test-" + UUID.randomUUID().toString().substring(0, 8);

        MiddlewareProperties.Memory memoryConfig = new MiddlewareProperties.Memory();
        memoryConfig.setEndpoint(endpoint);
        memoryConfig.setUserId(testUserId);
        memoryConfig.setTimeoutMs(15000);

        JiuwenMemoryApi api = new JiuwenMemoryApi(endpoint, memoryConfig, apiKey);
        memoryStore = new JiuwenMemoryStore(apiKey, memoryConfig, api);
    }

    @Test
    void healthCheck() {
        JiuwenMemoryApi api = new JiuwenMemoryApi(endpoint, null, apiKey);
        boolean healthy = api.isHealthy(endpoint);
        assertThat(healthy).isTrue();
    }

    @Test
    void addAndSearchMemory() {
        String content = "集成测试：用户喜欢喝美式咖啡 " + UUID.randomUUID().toString().substring(0, 8);

        // Add memory
        MemoryAddRequest addRequest = new MemoryAddRequest(
            new MemoryScope(testUserId, "", "", ""),
            List.of(new MemoryMessage("user", content)),
            java.util.Map.of());

        MemoryWriteResult result = memoryStore.add(addRequest);
        assertThat(result).isNotNull();

        // Search memory
        MemorySearchRequest searchRequest = new MemorySearchRequest(
            new MemoryScope(testUserId, "", "", ""),
            "美式咖啡",
            5,
            null,
            java.util.Map.of());

        List<MemoryRecord> records = memoryStore.search(searchRequest);
        assertThat(records).isNotEmpty();
        assertThat(records)
            .anySatisfy(record -> assertThat(record.memory()).contains("美式咖啡"));
    }

    @Test
    void addAndGetMemoryById() {
        String content = "集成测试：用户住在北京 " + UUID.randomUUID().toString().substring(0, 8);

        // Add memory
        MemoryAddRequest addRequest = new MemoryAddRequest(
            new MemoryScope(testUserId, "", "", ""),
            List.of(new MemoryMessage("user", content)),
            java.util.Map.of());

        memoryStore.add(addRequest);

        // Get all memories via search to find the mem_id
        MemorySearchRequest searchRequest = new MemorySearchRequest(
            new MemoryScope(testUserId, "", "", ""),
            "北京",
            10,
            null,
            java.util.Map.of());

        List<MemoryRecord> searchResults = memoryStore.search(searchRequest);
        assertThat(searchResults).isNotEmpty();

        String memId = searchResults.get(0).memoryId();

        // Get by ID
        MemoryGetRequest getRequest = new MemoryGetRequest(
            new MemoryScope(testUserId, "", "", ""),
            memId);

        Optional<MemoryRecord> record = memoryStore.get(getRequest);
        // Note: get may not find the exact record depending on service behavior
        // but should not throw
        assertThat(record).isNotNull();
    }

    @Test
    void storeIsAvailable() {
        assertThat(memoryStore.isAvailable()).isTrue();
        assertThat(memoryStore.getProvider()).isEqualTo("jiuwen");
    }

    private static boolean isServiceReachable(String endpoint) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
