/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.demo.example.support.MemoryTurnWriteRegistry;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Unit tests for the turn-write suppression in {@link MemoryLifecycleAgentHandler}.
 *
 * @since 0.1.0
 */
class MemoryLifecycleAgentHandlerTest {
    @Test
    void querySkipsTurnWriteWhenToolWritesDuringTurn() {
        RecordingMemoryStore store = new RecordingMemoryStore();
        MemoryTurnWriteRegistry registry = new MemoryTurnWriteRegistry();
        MemoryLifecycleAgentHandler handler = newHandler(store, registry,
            ignored -> registry.markToolWrite("conv-1"));

        handler.query(request("conv-1", "请记住：我下周三去北京出差"));

        // 工具已写：本轮不再写回，避免二次抽取与重复单元
        assertThat(store.addRequests).isEmpty();
    }

    @Test
    void queryWritesTurnWhenToolDidNotWrite() {
        RecordingMemoryStore store = new RecordingMemoryStore();
        MemoryLifecycleAgentHandler handler = newHandler(store, new MemoryTurnWriteRegistry(), ignored -> { });

        handler.query(request("conv-2", "我下周三去北京出差"));

        assertThat(store.addRequests).hasSize(1);
        assertThat(store.addRequests.get(0).messages())
            .extracting(message -> message.content())
            .contains("我下周三去北京出差");
    }

    @Test
    void queryDropsStaleAnnouncementLeftByPreviousTurn() {
        // 上一轮工具写后异常结束、没消费登记：本轮是普通对话，必须照常回写
        RecordingMemoryStore store = new RecordingMemoryStore();
        MemoryTurnWriteRegistry registry = new MemoryTurnWriteRegistry();
        registry.markToolWrite("conv-3");
        MemoryLifecycleAgentHandler handler = newHandler(store, registry, ignored -> { });

        handler.query(request("conv-3", "今天天气怎么样"));

        assertThat(store.addRequests).hasSize(1);
    }

    private static MemoryLifecycleAgentHandler newHandler(RecordingMemoryStore store,
        MemoryTurnWriteRegistry registry, Consumer<String> duringDelegate) {
        MemoryStoreMemoryProvider provider = new MemoryStoreMemoryProvider(store, new MiddlewareProperties.Memory());
        return new MemoryLifecycleAgentHandler(new EchoHandler(duringDelegate), provider, registry);
    }

    private static ServeRequest request(String conversationId, String userText) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", userText);
        request.setMessages(new ArrayList<>(List.of(message)));
        return request;
    }

    private static final class EchoHandler implements AgentHandler {
        private final Consumer<String> duringDelegate;

        private EchoHandler(Consumer<String> duringDelegate) {
            this.duringDelegate = duringDelegate;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            // 模拟模型在本轮内调用 memory_add 工具
            duringDelegate.accept(request.getConversationId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", "已记住");
            return new QueryResponse(result, request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onComplete();
        }
    }

    private static final class RecordingMemoryStore implements MemoryStore {
        private final List<MemoryAddRequest> addRequests = new ArrayList<>();

        @Override
        public String getProvider() {
            return "recording";
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
            return List.of();
        }

        @Override
        public Optional<MemoryRecord> get(MemoryGetRequest request) {
            return Optional.empty();
        }

        @Override
        public void delete(MemoryDeleteRequest request) {
            // no-op
        }
    }
}
