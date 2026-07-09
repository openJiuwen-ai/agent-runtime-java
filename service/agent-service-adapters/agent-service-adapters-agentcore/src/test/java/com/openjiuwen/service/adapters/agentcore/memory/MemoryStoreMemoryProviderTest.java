/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemoryScope;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests the runtime-to-core memory provider bridge.
 *
 * @since 0.1.0
 */
class MemoryStoreMemoryProviderTest {
    @Test
    void prefetchSearchesMemoryStoreAndFormatsPromptBlock() throws Exception {
        RecordingMemoryStore store = new RecordingMemoryStore(List.of(
            new MemoryRecord("m-1", "用户喜欢拿铁", Map.of("source", "test"), Map.of("score", 0.9)),
            new MemoryRecord("m-2", "", Map.of(), Map.of())));
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setUserId("configured-user");
        memory.setAgentId("configured-agent");
        memory.setRerank(true);

        MemoryStoreMemoryProvider provider = new MemoryStoreMemoryProvider(store, memory);

        String block = provider.prefetch("饮品偏好", Map.of(
            "user_id", "request-user",
            "scope_id", "space-1",
            "session_id", "session-1"));

        assertThat(block)
            .contains("## Long-term Memory")
            .contains("用户喜欢拿铁")
            .doesNotContain("m-2");
        assertThat(store.searchRequests).singleElement().satisfies(request -> {
            assertThat(request.query()).isEqualTo("饮品偏好");
            assertThat(request.topK()).isEqualTo(5);
            assertThat(request.rerank()).isTrue();
            assertThat(request.scope().userId()).isEqualTo("request-user");
            assertThat(request.scope().agentId()).isBlank();
            assertThat(request.scope().scopeId()).isEqualTo("space-1");
            assertThat(request.scope().sessionId()).isEqualTo("session-1");
        });
    }

    @Test
    void syncTurnWritesConversationMessagesToMemoryStore() throws Exception {
        RecordingMemoryStore store = new RecordingMemoryStore(List.of());
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setUserId("configured-user");
        memory.setAgentId("configured-agent");
        MemoryStoreMemoryProvider provider = new MemoryStoreMemoryProvider(store, memory);

        provider.syncTurn("请记住我喜欢拿铁", "已记住", Map.of("user_id", "request-user"));

        assertThat(store.addRequests).singleElement().satisfies(request -> {
            assertThat(request.scope().userId()).isEqualTo("request-user");
            assertThat(request.scope().agentId()).isBlank();
            assertThat(request.messages()).extracting("role").containsExactly("user", "assistant");
            assertThat(request.messages()).extracting("content").containsExactly("请记住我喜欢拿铁", "已记住");
            assertThat(request.options()).containsEntry("infer", true);
        });
    }

    @Test
    void explicitAgentIdIsPreservedWhenProvidedByCaller() throws Exception {
        RecordingMemoryStore store = new RecordingMemoryStore(List.of());
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memory.setUserId("configured-user");
        memory.setAgentId("configured-agent");
        MemoryStoreMemoryProvider provider = new MemoryStoreMemoryProvider(store, memory);

        provider.prefetch("饮品偏好", Map.of("user_id", "request-user", "agent_id", "request-agent"));

        assertThat(store.searchRequests).singleElement().satisfies(request -> {
            assertThat(request.scope().userId()).isEqualTo("request-user");
            assertThat(request.scope().agentId()).isEqualTo("request-agent");
        });
    }

    private static final class RecordingMemoryStore implements MemoryStore {
        private final List<MemoryRecord> records;
        private final List<MemorySearchRequest> searchRequests = new ArrayList<>();
        private final List<MemoryAddRequest> addRequests = new ArrayList<>();

        private RecordingMemoryStore(List<MemoryRecord> records) {
            this.records = records;
        }

        @Override
        public String getProvider() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public MemoryWriteResult add(MemoryAddRequest request) {
            addRequests.add(request);
            return new MemoryWriteResult(List.of(), Map.of());
        }

        @Override
        public List<MemoryRecord> search(MemorySearchRequest request) {
            searchRequests.add(request);
            return records;
        }

        @Override
        public Optional<MemoryRecord> get(MemoryGetRequest request) {
            return Optional.empty();
        }

        @Override
        public void delete(MemoryDeleteRequest request) {
        }
    }
}
